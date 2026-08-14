/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.email.streams

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.{ any, eq as is }
import org.mockito.Mockito
import org.mockito.Mockito.{ times, verify, when }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.config.EventHubStreamConfig
import uk.gov.hmrc.email.connectors.{ ErrorMessage, EventHubConnector, EventHubResponse }
import uk.gov.hmrc.email.model.Tag
import uk.gov.hmrc.email.repositories.{ EventHubItem, EventHubRepository }
import uk.gov.hmrc.email.util.LogCapturing
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ InProgress, Succeeded }
import uk.gov.hmrc.mongo.workitem.{ ResultStatus, WorkItem }
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

class EventHubStreamSpec extends SpecBase with ScalaFutures with LogCapturing with BeforeAndAfterAll {

  override protected def afterAll(): Unit = {
    val _ = ActorSystem().terminate()
  }

  "The EventHubStream" should {

    val eventId = UUID.randomUUID()
    val eventId2 = UUID.randomUUID()
    val eventId3 = UUID.randomUUID()
    val dateTime = Instant.now
    val dateTimeInstant = Instant.now()

    val event1 = EventHubItem(
      id = "mailgun event id",
      eventId,
      emailAddress = "test@test.com",
      detected = dateTime,
      eventType = "failed",
      reason = "some reason",
      tags = Map("enrolment" -> "some enrolment"),
      code = Some(500),
      messageId = "mailgun message id",
      template = Some(Tag("template-id"))
    )
    val event2 = event1.copy(eventId = eventId2, emailAddress = "test2@test.com")
    val event3 = event1.copy(eventId = eventId3, emailAddress = "test3@test.com")

    val workItem1 = WorkItem[EventHubItem](
      new ObjectId(),
      receivedAt = dateTimeInstant,
      updatedAt = dateTimeInstant,
      availableAt = dateTimeInstant,
      status = InProgress,
      failureCount = 0,
      item = event1
    )
    val workItem2 = workItem1.copy(id = new ObjectId(), item = event2)
    val workItem3 = workItem1.copy(id = new ObjectId(), item = event3)

    "process elements in the event-hub collection when available" in new TestCase {

      when(eventHubRepositoryMock.pullOutstandingEventHubItem).thenReturn(
        Future.successful(Some(workItem1)),
        Future.successful(Some(workItem2)),
        Future.successful(Some(workItem3)),
        Future.successful(None)
      )

      when(eventHubConnectorMock.publishEventHubItem(any[EventHubItem]())(any[HeaderCarrier]))
        .thenReturn(Future.successful(Right(EventHubResponse("valid response"))))

      when(eventHubRepositoryMock.complete(any[ObjectId](), any[ResultStatus]()))
        .thenReturn(
          Future.successful(true)
        )

      eventHubStream.start(true)

      oneMinute {
        verify(eventHubRepositoryMock, times(4)).pullOutstandingEventHubItem
      }

      oneMinute {
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event1))(any[HeaderCarrier])
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event2))(any[HeaderCarrier])
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event3))(any[HeaderCarrier])
        verify(eventHubRepositoryMock, times(1)).complete(workItem1.id, Succeeded)
        verify(eventHubRepositoryMock, times(1)).complete(workItem2.id, Succeeded)
        verify(eventHubRepositoryMock, times(1)).complete(workItem3.id, Succeeded)
      }
    }

    "do not process events if eventHubEnabled is false" in new TestCase {
      when(eventHubRepositoryMock.pullOutstandingEventHubItem)
        .thenReturn(Future.successful(Some(workItem1)))

      eventHubStream.start(eventHubEnabled = false)

      oneMinute {
        verify(eventHubRepositoryMock, Mockito.never()).pullOutstandingEventHubItem
      }
    }

    "mark elements in the event-hub collection as failed when event-hub responds with an error state" in new TestCase {

      when(eventHubRepositoryMock.pullOutstandingEventHubItem)
        .thenReturn(
          Future.successful(Some(workItem1)),
          Future.successful(Some(workItem2)),
          Future.successful(Some(workItem3)),
          Future.successful(None)
        )

      when(eventHubConnectorMock.publishEventHubItem(any[EventHubItem]())(any[HeaderCarrier]))
        .thenReturn(Future.successful(Left(ErrorMessage("error response"))))

      when(eventHubRepositoryMock.failEventHubItem(any[WorkItem[EventHubItem]]))
        .thenReturn(
          Future.successful(true)
        )

      eventHubStream.start(true)

      oneMinute {
        verify(eventHubRepositoryMock, times(4)).pullOutstandingEventHubItem
      }

      oneMinute {
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event1))(any[HeaderCarrier])
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event2))(any[HeaderCarrier])
        verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event3))(any[HeaderCarrier])
        verify(eventHubRepositoryMock, times(1)).failEventHubItem(workItem1)
        verify(eventHubRepositoryMock, times(1)).failEventHubItem(workItem2)
        verify(eventHubRepositoryMock, times(1)).failEventHubItem(workItem3)
      }
    }

    "mark elements in the event-hub collection as permanently failed when event-hub responds with an error state " +
      "beyond the configured 'max retries' value" in new TestCase {

        val workItemWithHighFailureCount: WorkItem[EventHubItem] =
          workItem1.copy(id = new ObjectId(), item = event3, failureCount = 3)

        when(eventHubRepositoryMock.pullOutstandingEventHubItem)
          .thenReturn(
            Future.successful(Some(workItem1)),
            Future.successful(Some(workItem2)),
            Future.successful(Some(workItemWithHighFailureCount)),
            Future.successful(None)
          )

        when(eventHubConnectorMock.publishEventHubItem(any[EventHubItem]())(any[HeaderCarrier]))
          .thenReturn(Future.successful(Left(ErrorMessage("error response"))))

        when(eventHubRepositoryMock.failEventHubItem(any[WorkItem[EventHubItem]]))
          .thenReturn(
            Future.successful(true)
          )

        when(eventHubRepositoryMock.permanentlyFailEventHubItem(any[WorkItem[EventHubItem]]))
          .thenReturn(
            Future.successful(true)
          )

        eventHubStream.start(true)

        oneMinute {
          verify(eventHubRepositoryMock, times(4)).pullOutstandingEventHubItem
        }

        oneMinute {
          verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event1))(any[HeaderCarrier])
          verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event2))(any[HeaderCarrier])
          verify(eventHubConnectorMock, times(1)).publishEventHubItem(is(event3))(any[HeaderCarrier])
          verify(eventHubRepositoryMock, times(1)).failEventHubItem(workItem1)
          verify(eventHubRepositoryMock, times(1)).failEventHubItem(workItem2)
          verify(eventHubRepositoryMock, times(1)).permanentlyFailEventHubItem(workItemWithHighFailureCount)
        }
      }
  }

  trait TestCase {
    implicit val actorSystem: ActorSystem = ActorSystem()
    implicit val actorMaterializer: Materializer = Materializer(actorSystem)
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val eventPollingInterval: FiniteDuration = 500.millis
    val eventMaxRetries = 3
    val elements = 6
    val per: FiniteDuration = 100.millis
    val minBackOff: FiniteDuration = 100.millis
    val maxBackOff: FiniteDuration = 100.millis

    def oneMinute[T](fun: => T): T = eventually(timeout(1.minute), interval(100.milliseconds))(fun)

    val eventHubStreamConfig: EventHubStreamConfig = EventHubStreamConfig(
      eventPollingInterval,
      eventMaxRetries,
      elements,
      per,
      minBackOff,
      maxBackOff
    )
    val eventHubRepositoryMock: EventHubRepository = mock[EventHubRepository]
    val eventHubConnectorMock: EventHubConnector = mock[EventHubConnector]
    val eventHubStream: EventHubStream = new EventHubStream(
      eventHubRepositoryMock,
      eventHubConnectorMock,
      eventHubStreamConfig
    )
  }
}

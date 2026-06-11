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

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.pattern.Patterns.after
import org.apache.pekko.stream.Attributes.LogLevels
import org.apache.pekko.stream.scaladsl.{ Keep, RestartSource, Sink, Source }
import org.apache.pekko.stream.{ Attributes, KillSwitches, Materializer, RestartSettings, SharedKillSwitch }
import java.util.concurrent.Callable
import javax.inject.{ Inject, Singleton }
import uk.gov.hmrc.email.repositories.{ EventHubItem, EventHubRepository }
import play.api.Logging
import uk.gov.hmrc.email.config.EventHubStreamConfig
import uk.gov.hmrc.email.connectors.EventHubConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EventHubStream @Inject() (
  eventHubRepository: EventHubRepository,
  eventHubConnector: EventHubConnector,
  eventHubStreamConfig: EventHubStreamConfig
)(implicit actorSystem: ActorSystem, materializer: Materializer, executionContext: ExecutionContext)
    extends Logging {

  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val killSwitch: SharedKillSwitch = KillSwitches.shared("event-hub-stream-kill-switch")

  val akkalog: LoggingAdapter = actorSystem.log

  def start(eventHubEnabled: Boolean): NotUsed =
    if (!eventHubEnabled)
      NotUsed
    else {
      akkalog.warning("Starting event-hub stream...")
      stream
        .to(
          Sink.foreachAsync(eventHubStreamConfig.elements)(eventHubWorkItem =>
            {
              akkalog.debug("Processing event-hub stream event...")
              eventHubConnector.publishEventHubItem(eventHubWorkItem.item) flatMap {
                case Right(_) => eventHubRepository.complete(eventHubWorkItem.id, ProcessingStatus.Succeeded)
                case Left(_) if eventHubWorkItem.failureCount < eventHubStreamConfig.eventMaxRetries =>
                  akkalog.error(
                    "Failed to send event to event-hub at current failureCount: " +
                      s"${eventHubWorkItem.failureCount} - failing event hub item in work-item queue..."
                  )
                  eventHubRepository.failEventHubItem(eventHubWorkItem)
                case _ =>
                  akkalog.error(
                    s"Failed to send event to event-hub beyond eventMaxRetries value: " +
                      s"${eventHubStreamConfig.eventMaxRetries} - *permanently* failing event hub item in work-item queue..."
                  )
                  eventHubRepository.permanentlyFailEventHubItem(eventHubWorkItem)
              }
            }.map(_ => ())
          )
        )
        .run()
    }

  def shutdown(): Unit = {
    akkalog.warning("Shutting down event-hub stream...")
    killSwitch.shutdown()
  }

  private def stream: Source[WorkItem[EventHubItem], NotUsed] =
    RestartSource.withBackoff(
      RestartSettings(
        eventHubStreamConfig.minBackOff,
        eventHubStreamConfig.maxBackOff,
        EventHubStreamConfig.RandomFactor
      )
    ) { () =>
      source
        .throttle(eventHubStreamConfig.elements, eventHubStreamConfig.per)
        .viaMat(killSwitch.flow)(Keep.left)
        .log(s"event-hub-stream")
        .withAttributes(
          Attributes.logLevels(
            onElement = LogLevels.Debug,
            onFinish = LogLevels.Error,
            onFailure = LogLevels.Error
          )
        )
    }

  private def source: Source[WorkItem[EventHubItem], NotUsed] =
    Source.unfoldAsync(())(onPull)

  private def onPull: Unit => Future[Option[(Unit, WorkItem[EventHubItem])]] = { _ =>
    akkalog.debug("Pulling event from event-hub work-item queue...")
    eventHubRepository.pullOutstandingEventHubItem.flatMap(pullLogic)
  }

  private def pullLogic(readResult: Option[WorkItem[EventHubItem]]): Future[Option[(Unit, WorkItem[EventHubItem])]] =
    readResult match {
      case None =>
        akkalog.debug(
          s"Event not found from event-hub work-item queue, waiting for ${eventHubStreamConfig.eventPollingInterval.toSeconds} seconds..."
        )
        after(eventHubStreamConfig.eventPollingInterval, actorSystem.scheduler, executionContext, onPullCallable)
      case Some(event) =>
        akkalog.debug("Found event from event-hub work-item queue...")
        Future.successful(Some(() -> event))
    }

  private val onPullCallable: Callable[Future[Option[(Unit, WorkItem[EventHubItem])]]] =
    new Callable[Future[Option[(Unit, WorkItem[EventHubItem])]]] {
      override def call: Future[Option[(Unit, WorkItem[EventHubItem])]] = onPull(())
    }
}

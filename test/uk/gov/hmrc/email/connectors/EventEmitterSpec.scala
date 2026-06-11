/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.Mockito.when
import org.mongodb.scala.bson.ObjectId
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.libs.json.{ JsResultException, Json }
import play.api.test.Helpers.*
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ EMPTY_STRING, TEST_DOMAIN, TEST_MESSAGE_ID, TEST_TIME_INSTANT }
import uk.gov.hmrc.email.model.EventType
import uk.gov.hmrc.email.repositories.EmailEventsRepository
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import uk.gov.hmrc.email.scheduled.EventEmitterConfig
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.InProgress
import uk.gov.hmrc.mongo.workitem.WorkItem
import org.mockito.ArgumentMatchers.any
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }

import java.net.URL
import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future }

class EventEmitterSpec extends SpecBase {

  "EventEmitterResults.formats" should {
    import EventEmitterResults.formats

    "read the json correctly" in new Setup {
      Json.parse(eventEmitterResultsJsonString).as[EventEmitterResults] mustBe eventEmitterResults
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventEmitterResultsInvalidJsonString).as[EventEmitterResults]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(eventEmitterResults) mustBe Json.parse(eventEmitterResultsJsonString)
    }
  }

  "EventEmitterResults.incrementEmitted" should {
    "increment the emitted value by 1" in new Setup {
      eventEmitterResults.incrementEmitted mustBe eventEmitterResults.copy(emitted = 2)
    }
  }

  "EventEmitterResults.incrementFailed" should {
    "increment the failed value by 1" in new Setup {
      eventEmitterResults.incrementFailed mustBe eventEmitterResults.copy(failed = 2)
    }
  }

  "EventEmitter.sendImiEvents" should {
    "mark the events complete and increase the relevant event emitter status" when {
      "work item evenUrl is present and events are posted successfully to the url" in new Setup {
        when(mockEmailEventsRepository.markComplete(any, any)).thenReturn(Future.successful(true))

        when(mockHttpClient.post(any[URL])(any[HeaderCarrier])).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.withBody(any)(using any, any, any)).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.execute[HttpResponse](using any, any))
          .thenReturn(Future.successful(HttpResponse(OK, EMPTY_STRING)))

        val result: EventEmitterResults = await(eventEmitter.sendImiEvents(eventEmitterResults, workItem))
        result mustBe EventEmitterResults(emitted = 2, failed = 1)
      }

      "work item evenUrl is present but events could not be posted successfully due to some connection error" in new Setup {
        when(mockEmailEventsRepository.markComplete(any, any)).thenReturn(Future.successful(true))

        when(mockHttpClient.post(any[URL])(any[HeaderCarrier])).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.withBody(any)(using any, any, any)).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.execute[HttpResponse](using any, any))
          .thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR, EMPTY_STRING)))

        val result: EventEmitterResults = await(eventEmitter.sendImiEvents(eventEmitterResults, workItem))
        result mustBe EventEmitterResults(emitted = 1, failed = 2)
      }
    }

    "mark the events complete" when {
      "there is no evenUrl in work item" in new Setup {
        when(mockEmailEventsRepository.markComplete(any, any)).thenReturn(Future.successful(true))

        val emailEventsItemWithNoEventUrl: EmailEventsItem = emailEventsItem.copy(eventUrl = None)

        val result: EventEmitterResults =
          await(eventEmitter.sendImiEvents(eventEmitterResults, workItem.copy(item = emailEventsItemWithNoEventUrl)))

        result mustBe EventEmitterResults(emitted = 1, failed = 1)
      }

      "there is evenUrl present in work item but exception occurs during processing" in new Setup {
        when(mockEmailEventsRepository.markComplete(any, any)).thenReturn(Future.successful(true))

        when(mockHttpClient.post(any[URL])(any[HeaderCarrier])).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.withBody(any)(using any, any, any)).thenReturn(mockRequestBuilder)
        when(mockRequestBuilder.execute[HttpResponse](using any, any))
          .thenReturn(Future.failed(RuntimeException("connection error")))

        val result: EventEmitterResults = await(eventEmitter.sendImiEvents(eventEmitterResults, workItem))
        result mustBe EventEmitterResults(emitted = 1, failed = 2)
      }
    }
  }

  "replace" should {
    "return the correct value" in new Setup {
      eventEmitter.replace("http://localhost:8080") must be("http://localhost:8080")
    }
  }

  trait Setup {
    val eventEmitterResults: EventEmitterResults = EventEmitterResults(emitted = 1, failed = 1)

    val eventEmitterResultsJsonString: String = """{"emitted":1,"failed":1}""".stripMargin
    val eventEmitterResultsInvalidJsonString: String = """{"failed":1}""".stripMargin

    val emailEventsItem: EmailEventsItem = EmailEventsItem(
      messageId = TEST_MESSAGE_ID,
      eventUrl = Some("http://localhost:8080"),
      events = Map(),
      emailSource = Some("test"),
      senderDomain = TEST_DOMAIN
    )

    val workItem: WorkItem[EmailEventsItem] = WorkItem[EmailEventsItem](
      id = new ObjectId(),
      receivedAt = TEST_TIME_INSTANT,
      updatedAt = TEST_TIME_INSTANT,
      status = InProgress,
      failureCount = 0,
      item = emailEventsItem,
      availableAt = TEST_TIME_INSTANT
    )

    val mockHttpClient: HttpClientV2 = mock[HttpClientV2]
    val mockRequestBuilder: RequestBuilder = mock[RequestBuilder]
    val mockEventEmitterConfig: EventEmitterConfig = mock[EventEmitterConfig]
    val mockEmailEventsRepository: EmailEventsRepository = mock[EmailEventsRepository]

    implicit val actorSystem: ActorSystem = ActorSystem()
    implicit val actorMaterializer: Materializer = Materializer(actorSystem)
    implicit val hc: HeaderCarrier = HeaderCarrier()
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    val eventEmitter = new EventEmitter(
      httpClient = mockHttpClient,
      eventEmitterConfig = mockEventEmitterConfig,
      emailEventsRepository = mockEmailEventsRepository
    )
  }
}

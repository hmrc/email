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

package uk.gov.hmrc.email.connectors

import ch.qos.logback.classic.Level
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{ eq as is, * }
import org.mockito.Mockito.when
import org.scalatest.Inspectors
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status
import play.api.libs.json.{ JsResultException, JsValue, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_ADDRESS_VALUE, TEST_EVENT, TEST_GROUP_ID, TEST_ID, TEST_LOCAL_DATETIME, TEST_MESSAGE, TEST_RANDOM_UUID, TEST_REASON, TEST_SUBJECT, TEST_TIME_INSTANT }
import uk.gov.hmrc.email.model.EventType.{ PermanentBounce, Rejected, TemporaryBounce }
import uk.gov.hmrc.email.model.Tag
import uk.gov.hmrc.email.repositories.EventHubItem
import uk.gov.hmrc.email.util.LogCapturing
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.*
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{ Instant, LocalDateTime }
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.reflectiveCalls
import uk.gov.hmrc.email.model.eventToString

class EventHubConnectorSpec
    extends SpecBase with ScalaFutures with LogCapturing with Eventually with Inspectors with MockitoSugar {

  type Hdrs = Seq[(String, String)]

  "The EventHubConnector" should {
    "return a right success response object when sending a valid mailgun event hub item to the event-hub service" in new TestCase {
      private val eventHubUrl: ArgumentCaptor[URL] = ArgumentCaptor.forClass(classOf[URL])
      private val eventHubRequest: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])
      private val recordedDateTime = LocalDateTime.now

      when(mockHttpClient.post(eventHubUrl.capture())(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.withBody(eventHubRequest.capture())(using any, any, any)).thenReturn(requestBuilder)
      when {
        requestBuilder.execute[HttpResponse](using any[HttpReads[HttpResponse]], any[ExecutionContext])
      }.thenReturn(Future.successful(HttpResponse(Status.CREATED, "created!")))

      val permanentEventHubItem: EventHubItem =
        EventHubItem(
          id = "mailgun event id permanent",
          eventId = eventId,
          emailAddress = "test@gmail.com",
          detected = Instant.now(),
          eventType = PermanentBounce.name,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500),
          messageId = "mailgun message id",
          Some(Tag("template-id"))
        )
      val temporaryEventHubItem: EventHubItem =
        permanentEventHubItem.copy(id = "mailgun event id temporary", eventType = TemporaryBounce)
      val rejectedEventHubItem: EventHubItem =
        permanentEventHubItem.copy(id = "mailgun event id rejected", eventType = Rejected)

      val permanentBounceResponse: Either[ErrorMessage, EventHubResponse] =
        eventHubConnector.publishEventHubItem(permanentEventHubItem).futureValue

      permanentBounceResponse.isRight mustBe true
      val ehr: EventHubResponse = permanentBounceResponse.getOrElse(EventHubResponse("fail!"))
      ehr mustBe EventHubResponse("created!")
      eventHubUrl.getValue.toString must be("http://some-domain:1234/publish/email")
      val requestValue = eventHubRequest.getValue.as[EventHubRequest]
      requestValue.subject must be("email")
      requestValue.groupId must be("mailgun message id")
      requestValue.timestamp.isAfter(recordedDateTime)
      requestValue.event must be(
        RequestEventHubItem(
          id = "mailgun event id permanent",
          emailAddress = "test@gmail.com",
          detected = permanentEventHubItem.detected,
          event = PermanentBounce,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500)
        )
      )

      val temporaryBounceResponse: Either[ErrorMessage, EventHubResponse] =
        eventHubConnector.publishEventHubItem(temporaryEventHubItem).futureValue

      temporaryBounceResponse.isRight mustBe true
      val tehr: EventHubResponse = temporaryBounceResponse.getOrElse(EventHubResponse("fail!"))
      tehr mustBe EventHubResponse("created!")

      eventHubUrl.getValue.toString must be("http://some-domain:1234/publish/email")
      val requestValue2 = eventHubRequest.getValue.as[EventHubRequest]
      requestValue2.subject must be("email")
      requestValue2.groupId must be("mailgun message id")
      requestValue2.timestamp.isAfter(recordedDateTime)
      requestValue2.event must be(
        RequestEventHubItem(
          id = "mailgun event id temporary",
          emailAddress = "test@gmail.com",
          detected = temporaryEventHubItem.detected,
          event = TemporaryBounce,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500)
        )
      )

      val rejectedBounceResponse: Either[ErrorMessage, EventHubResponse] =
        eventHubConnector.publishEventHubItem(rejectedEventHubItem).futureValue

      rejectedBounceResponse.isRight mustBe true
      val rehr = rejectedBounceResponse.getOrElse(EventHubResponse("fail!"))
      rehr mustBe EventHubResponse("created!")
      eventHubUrl.getValue.toString must be("http://some-domain:1234/publish/email")
      val requestValue3 = eventHubRequest.getValue.as[EventHubRequest]
      requestValue3.subject must be("email")
      requestValue3.groupId must be("mailgun message id")
      requestValue3.timestamp.isAfter(recordedDateTime)
      requestValue3.event must be(
        RequestEventHubItem(
          id = "mailgun event id rejected",
          emailAddress = "test@gmail.com",
          detected = rejectedEventHubItem.detected,
          event = Rejected,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500)
        )
      )

      eventually {
        auditEvents must have(size(3))

        forAtLeast(1, auditEvents) { auditEvent =>
          auditEvent.auditSource must be("email")
          auditEvent.auditType must be("TxSucceeded")
          auditEvent.tags("transactionName") must be("Permanent Bounced")
          auditEvent.detail must be(
            Map(
              "emailMessageId" -> "mailgun message id",
              "emailAddress"   -> "test@gmail.com",
              "eventId"        -> auditEvent.detail("eventId"),
              "emailEventId"   -> "mailgun event id permanent",
              "detected" -> DateTimeFormatter.ISO_INSTANT
                .format(permanentEventHubItem.detected.truncatedTo(ChronoUnit.MILLIS)),
              "statusCode" -> "201",
              "tags"       -> Map("enrolment" -> "some enrolment", "templateId" -> "template-id").mkString(","),
              "templateId" -> "template-id"
            )
          )
        }

        forAtLeast(1, auditEvents) { auditEvent =>
          auditEvent.auditSource must be("email")
          auditEvent.auditType must be("TxSucceeded")
          auditEvent.tags("transactionName") must be("Temporarily Bounced")
          auditEvent.detail must be(
            Map(
              "emailMessageId" -> "mailgun message id",
              "emailAddress"   -> "test@gmail.com",
              "eventId"        -> auditEvent.detail("eventId"),
              "emailEventId"   -> "mailgun event id temporary",
              "detected" -> DateTimeFormatter.ISO_INSTANT
                .format(temporaryEventHubItem.detected.truncatedTo(ChronoUnit.MILLIS)),
              "statusCode" -> "201",
              "tags"       -> Map("enrolment" -> "some enrolment", "templateId" -> "template-id").mkString(","),
              "templateId" -> "template-id"
            )
          )
        }

        forAtLeast(1, auditEvents) { auditEvent =>
          auditEvent.auditSource must be("email")
          auditEvent.auditType must be("TxSucceeded")
          auditEvent.tags("transactionName") must be("Rejected")
          auditEvent.detail must be(
            Map(
              "emailMessageId" -> "mailgun message id",
              "emailAddress"   -> "test@gmail.com",
              "eventId"        -> auditEvent.detail("eventId"),
              "emailEventId"   -> "mailgun event id rejected",
              "detected" -> DateTimeFormatter.ISO_INSTANT
                .format(rejectedEventHubItem.detected.truncatedTo(ChronoUnit.MILLIS)),
              "statusCode" -> "201",
              "tags"       -> Map("enrolment" -> "some enrolment", "templateId" -> "template-id").mkString(","),
              "templateId" -> "template-id"
            )
          )
        }
      }
    }

    "return a left error object when sending an mailgun event hub item to the event-hub service and an exception is thrown" in new TestCase {

      when(mockHttpClient.post(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.withBody(any)(using any, any, any)).thenReturn(requestBuilder)
      when {
        requestBuilder.execute[HttpResponse](using any[HttpReads[HttpResponse]], any[ExecutionContext])
      }.thenReturn(Future.failed(new BadRequestException("Invalid payload format")))

      val permanentEventHubItem: EventHubItem =
        EventHubItem(
          id = "(((SOME_ID)))",
          eventId = eventId,
          emailAddress = "test@gmail.com",
          detected = Instant.now,
          eventType = PermanentBounce.name,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500),
          messageId = "*** MAILGUN_ID ***",
          None
        )

      withCaptureOfLoggingFrom[EventHubConnector] { logEvents =>
        val actualResponse: Either[ErrorMessage, EventHubResponse] =
          eventHubConnector.publishEventHubItem(permanentEventHubItem).futureValue

        actualResponse.isLeft mustBe true
        actualResponse.left.toOption.get mustBe ErrorMessage.fromExceptionReason("Invalid payload format")

        logEvents(1).getLevel must be(Level.ERROR)
        logEvents(1).getMessage must be(
          "Event-hub Connector POST error for messageId *** MAILGUN_ID ***: Invalid payload format"
        )
      }

      eventually {
        auditEvents must have(size(1))

        forAtLeast(1, auditEvents) { auditEvent =>
          auditEvent.auditSource must be("email")
          auditEvent.auditType must be("TxFailed")
          auditEvent.tags("transactionName") must be("Permanent Bounced")
          auditEvent.detail must be(
            Map(
              "emailMessageId" -> "*** MAILGUN_ID ***",
              "emailAddress"   -> "test@gmail.com",
              "eventId"        -> auditEvent.detail("eventId"),
              "emailEventId"   -> "(((SOME_ID)))",
              "detected" -> DateTimeFormatter.ISO_INSTANT
                .format(permanentEventHubItem.detected.truncatedTo(ChronoUnit.MILLIS)),
              "statusCode" -> "0",
              "tags"       -> Map("enrolment" -> "some enrolment", "templateId" -> "template-id").mkString(","),
              "templateId" -> "template-id"
            )
          )
        }
      }
    }

    "return a right failed response object when the event-hub responds with a status other than 201" in new TestCase {
      private val eventHubUrl: ArgumentCaptor[URL] = ArgumentCaptor.forClass(classOf[URL])
      private val eventHubRequest: ArgumentCaptor[JsValue] = ArgumentCaptor.forClass(classOf[JsValue])
      private val recordedDateTime = LocalDateTime.now

      when(mockHttpClient.post(eventHubUrl.capture())(any[HeaderCarrier])).thenReturn(requestBuilder)
      when(requestBuilder.withBody(eventHubRequest.capture())(using any, any, any)).thenReturn(requestBuilder)

      when {
        requestBuilder.execute[HttpResponse](using any[HttpReads[HttpResponse]], any[ExecutionContext])
      }.thenReturn(Future.successful(HttpResponse(Status.IM_A_TEAPOT, "I'm a teapot!")))

      val permanentEventHubItem: EventHubItem =
        EventHubItem(
          id = "(((SOME_ID)))",
          eventId = eventId,
          emailAddress = "test@gmail.com",
          detected = Instant.now,
          eventType = PermanentBounce.name,
          reason = "a particular reason",
          tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
          code = Some(500),
          messageId = "*** MAILGUN_ID ***",
          template = Some(Tag("template-id"))
        )

      withCaptureOfLoggingFrom[EventHubConnector] { logEvents =>
        val permanentBounceResponse: Either[ErrorMessage, EventHubResponse] =
          eventHubConnector.publishEventHubItem(permanentEventHubItem).futureValue

        permanentBounceResponse.isRight mustBe true
        val pehr = permanentBounceResponse.getOrElse(EventHubResponse("fail!"))
        pehr mustBe EventHubResponse("I'm a teapot!")
        eventHubUrl.getValue.toString must be("http://some-domain:1234/publish/email")
        val request = eventHubRequest.getValue.as[EventHubRequest]
        request.subject must be("email")
        request.groupId must be("*** MAILGUN_ID ***")
        request.timestamp.isAfter(recordedDateTime)
        request.event must be(
          RequestEventHubItem(
            id = "(((SOME_ID)))",
            emailAddress = "test@gmail.com",
            detected = permanentEventHubItem.detected,
            event = PermanentBounce,
            reason = "a particular reason",
            tags = Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
            code = Some(500)
          )
        )
        logEvents(1).getLevel must be(Level.DEBUG)
        logEvents(1).getMessage must be("Response from event-hub for messageId *** MAILGUN_ID ***: I'm a teapot!")
      }

      eventually {
        auditEvents must have(size(1))

        forAtLeast(1, auditEvents) { auditEvent =>
          auditEvent.auditSource must be("email")
          auditEvent.auditType must be("TxFailed")
          auditEvent.tags("transactionName") must be("Permanent Bounced")
          auditEvent.detail must be(
            Map(
              "emailMessageId" -> "*** MAILGUN_ID ***",
              "emailAddress"   -> "test@gmail.com",
              "eventId"        -> auditEvent.detail("eventId"),
              "emailEventId"   -> "(((SOME_ID)))",
              "detected" -> DateTimeFormatter.ISO_INSTANT
                .format(permanentEventHubItem.detected.truncatedTo(ChronoUnit.MILLIS)),
              "statusCode" -> Status.IM_A_TEAPOT.toString,
              "tags"       -> Map("enrolment" -> "some enrolment", "templateId" -> "template-id").mkString(","),
              "templateId" -> "template-id"
            )
          )
        }
      }
    }
  }

  "RequestEventHubItem.requestEventHubItem" should {
    import RequestEventHubItem.requestEventHubItem

    "read the json correctly" in new Setup {
      Json.parse(reqEventHubItemJsonString).as[RequestEventHubItem] mustBe reqEventHubItem
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(reqEventHubItemInvalidJsonString).as[RequestEventHubItem]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(reqEventHubItem) mustBe Json.parse(reqEventHubItemJsonString)
    }
  }

  "EventHubRequest.eventHubRequestFormat" should {
    import EventHubRequest.eventHubRequestFormat

    "read the json correctly" in new Setup {
      Json.parse(eventHubRequestJsonString).as[EventHubRequest] mustBe eventHubRequest
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventHubRequestInvalidJsonString).as[EventHubRequest]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(eventHubRequest) mustBe Json.parse(eventHubRequestJsonString)
    }
  }

  "EventHubResponse.eventHubResponseFormat" should {
    import EventHubResponse.eventHubResponseFormat

    "read the json correctly" in new Setup {
      Json.parse(eventHubResponseJsonString).as[EventHubResponse] mustBe eventHubResponse
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventHubResponseInvalidJsonString).as[EventHubResponse]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(eventHubResponse) mustBe Json.parse(eventHubResponseJsonString)
    }
  }

  trait TestCase {
    implicit val patienceConfig: PatienceConfig =
      PatienceConfig(
        timeout = scaled(Span(60, Seconds)),
        interval = scaled(Span(150, Millis))
      )

    val mockHttpClient: HttpClientV2 = mock[HttpClientV2]
    val requestBuilder: RequestBuilder = mock[RequestBuilder]
    val mockServicesConfig: ServicesConfig = mock[ServicesConfig]

    var auditEvents: List[DataEvent] = List.empty

    extension (auditConnector: AuditConnector) def auditEvents: List[DataEvent] = List.empty

    val fakeAuditConnector: AuditConnector = new AuditConnector {

      override def auditingConfig: AuditingConfig = ???

      override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
        Future
          .successful(AuditResult.Success)
          .andThen { case _ =>
            auditEvents = event.asInstanceOf[DataEvent] :: auditEvents
          }(ec)

      override def auditChannel: AuditChannel = ???

      override def datastreamMetrics: DatastreamMetrics = ???
    }

    val eventHubConnector: EventHubConnector =
      new EventHubConnector(mockServicesConfig, mockHttpClient, fakeAuditConnector)

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val eventId: UUID = UUID.randomUUID()

    when(mockServicesConfig.baseUrl(is("event-hub"))).thenReturn("http://some-domain:1234")
    when(mockServicesConfig.getString(is("streams.event-hub.uri.path"))).thenReturn("/publish/email")
  }

  trait Setup {
    val reqEventHubItem: RequestEventHubItem = RequestEventHubItem(
      id = TEST_ID,
      emailAddress = TEST_EMAIL_ADDRESS_VALUE,
      detected = TEST_TIME_INSTANT,
      event = TEST_EVENT,
      reason = TEST_REASON,
      tags = Map("enrolment" -> "some enrolment"),
      code = Some(1)
    )

    val eventHubRequest: EventHubRequest = EventHubRequest(
      eventId = TEST_RANDOM_UUID,
      subject = TEST_SUBJECT,
      groupId = TEST_GROUP_ID,
      timestamp = TEST_LOCAL_DATETIME,
      event = reqEventHubItem
    )

    val eventHubResponse: EventHubResponse = EventHubResponse(message = TEST_MESSAGE)

    val reqEventHubItemJsonString: String =
      """{
        |"id":"test_id",
        |"emailAddress":"test@test.com",
        |"detected":"1970-01-01T18:11:18.234Z",
        |"event":"test_event",
        |"reason":"test_reason",
        |"tags":{"enrolment":"some enrolment"},
        |"code":1
        |}""".stripMargin

    val reqEventHubItemInvalidJsonString: String =
      """{
        |"emailAddress":"test@test.com",
        |"detected":"1970-01-01T18:11:18.234Z",
        |"event":"test_event",
        |"reason":"test_reason",
        |"tags":{"enrolment":"some enrolment"},
        |"code":1
        |}""".stripMargin

    val eventHubRequestJsonString: String =
      s"""{
         |"eventId":"$TEST_RANDOM_UUID",
         |"subject":"test_sub",
         |"groupId":"test_group_id",
         |"timestamp":"2025-12-06T11:30:50",
         |"event":{
         |"id":"test_id",
         |"emailAddress":"test@test.com",
         |"detected":"1970-01-01T18:11:18.234Z",
         |"event":"test_event",
         |"reason":"test_reason",
         |"tags":{"enrolment":"some enrolment"},"code":1}
         |}""".stripMargin

    val eventHubRequestInvalidJsonString: String =
      """{
        |"subject":"test_sub",
        |"groupId":"test_group_id",
        |"timestamp":"2025-12-06T11:30:50",
        |"event":{
        |"id":"test_id",
        |"emailAddress":"test@test.com",
        |"detected":"1970-01-01T18:11:18.234Z",
        |"event":"test_event",
        |"reason":"test_reason",
        |"tags":{"enrolment":"some enrolment"},"code":1}
        |}""".stripMargin

    val eventHubResponseJsonString: String = """{"message":"test_message"}""".stripMargin
    val eventHubResponseInvalidJsonString: String = """{}""".stripMargin
  }
}

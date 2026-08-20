/*
 * Copyright 2026 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status
import play.api.libs.json.Json
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.services.SenderDomainConfiguration
import uk.gov.hmrc.email.utils.Encryption
import uk.gov.hmrc.email.{ FakeSenderDomainConfiguration, SpecBase }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent
import java.net.URL
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class ImiConnectorSpec extends SpecBase with ScalaFutures with MockitoSugar {

  "send" must {
    "return an ImiSendResponse for a successful request" in new TestSetUp {

      private val result = imiConnector.send(emailContent).futureValue

      result mustBe
        Right(
          ImiSendResponse(
            "2023-01-06T15:30:11.676Z",
            "c06bcfad-21ca-4ebd-a805-e220419e9a35",
            "ded81d59-9e8a-4806-b5d1-a82ea350b056",
            "queued"
          )
        )
    }

    "return ImiError for unauthorized request" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(Status.UNAUTHORIZED, "")))

      private val result = imiConnector.send(emailContent).futureValue

      result mustBe (Left(ImiError("AUTHENTICATION_FAILED", "Imi Authentication failed")))
    }

    "return ImiError for bad request with response body" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(
        Future.successful(
          HttpResponse.apply(
            Status.BAD_REQUEST,
            """{ "detail" : { "response_message" : { "code" : "7020", "message" : "You have reached maximum transaction limit (rate)." }, "status_code" : "400" }, "generated_at" : "2023-09-01T11:11:19.013Z", "tags" : { } }"""
          )
        )
      )

      private val result = imiConnector.send(emailContent).futureValue

      result mustBe Left(
        ImiError(
          "INVALID_PAYLOAD",
          """{"detail":{"response_message":{"code":"7020","message":"You have reached maximum transaction limit (rate)."},"status_code":"400"},"generated_at":"2023-09-01T11:11:19.013Z","tags":{}}"""
        )
      )

    }
  }

  "addConsent" must {
    "return true if status is 204 from IMI" in new TestSetUp {

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(204, jsonResponse)))

      private val result =
        imiConnector.addConsent(emailAddress.value, groupId, true, "Email force requested").futureValue
      result mustBe true
    }

    "throw RuntimeException for addConsent with error" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(
        Future.successful(
          HttpResponse(Status.BAD_REQUEST, jsonResponse)
        )
      )

      assertThrows[RuntimeException](
        imiConnector.addConsent(emailAddress.value, groupId, true, "Email force requested").futureValue
      )
    }
  }

  "getConsent" must {
    "return 200 response" in new TestSetUp {

      private val payload = Json.parse("""[
                                         |    {
                                         |        "channel": "email",
                                         |        "address": "test@digital.hmrc.gov.uk",
                                         |        "consent": true,
                                         |        "reason": "Email force requested",
                                         |        "lastUpdated": "2023-06-27T13:33:51.914Z"
                                         |    }
                                         |]""".stripMargin)

      private val consentItems = payload.as[List[ConsentItem]]

      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(Future.successful(consentItems))
      private val result = imiConnector.getConsent(emailAddress, groupId).futureValue
      result.nonEmpty mustBe true
    }
  }

  "getConsentList" must {
    "return consent list when there is no continue token in header" in new TestSetUp {
      private val consentListJson = Json.parse("""[
                                                 |    {
                                                 |        "channel": "email",
                                                 |        "address": "test@digital.hmrc.gov.uk",
                                                 |        "consent": true,
                                                 |        "reason": "Email force requested",
                                                 |        "lastUpdated": "2023-06-27T13:33:51.914Z"
                                                 |    }
                                                 |]""".stripMargin)

      private val consentItems = consentListJson.as[List[ConsentItem]]
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(200, consentListJson, Map.empty)))
      private val result = imiConnector.getConsentList(groupId, None, 100).futureValue
      result.items mustBe consentItems
      result.continueToken mustBe None
    }

    "return consent list when there is continue token in header" in new TestSetUp {

      private val responseData = Json.parse("""[
                                              |    {
                                              |        "channel": "email",
                                              |        "address": "test@digital.hmrc.gov.uk",
                                              |        "consent": true,
                                              |        "reason": "Email force requested",
                                              |        "lastUpdated": "2023-06-27T13:33:51.914Z"
                                              |    }
                                              |]""".stripMargin)

      private val consentItems = responseData.as[List[ConsentItem]]

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(200, responseData, Map("X-cp-continue-token" -> Seq("123456789")))))

      private val result = imiConnector.getConsentList(groupId, None, 100).futureValue
      result.items mustBe consentItems
      result.continueToken mustBe Some("123456789")
    }

    "return consent list when there is no content" in new TestSetUp {

      private val responseData = Json.parse("""[]""".stripMargin)

      private val consentItems = responseData.as[List[ConsentItem]]

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(200, responseData, Map("X-cp-continue-token" -> Seq("123456789")))))

      private val result = imiConnector.getConsentList(groupId, None, 100).futureValue
      result.items mustBe consentItems
      result.continueToken mustBe Some("123456789")
    }

  }

  "deleteConsent" must {
    "return DeleteConsentSuccess for 200 status from IMI" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(Future.successful(HttpResponse(200, "")))

      val result: DeleteConsentResponse = imiConnector.deleteConsent(emailAddress, groupId).futureValue
      result mustBe DeleteConsentSuccess
    }
    "return DeleteConsentNotFound for 404 status" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(404, "")))

      val result: DeleteConsentResponse = imiConnector.deleteConsent(emailAddress, groupId).futureValue
      result mustBe DeleteConsentNotFound
    }
    "return DeleteConsentFailed for 400 status" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(400, "")))

      val result: DeleteConsentResponse = imiConnector.deleteConsent(emailAddress, groupId).futureValue
      result mustBe DeleteConsentFailed(Some(400), "Failed to delete 400")
    }
    "return DeleteConsentFailed for 500 status" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(500, "")))

      val result: DeleteConsentResponse = imiConnector.deleteConsent(emailAddress, groupId).futureValue
      result mustBe DeleteConsentFailed(Some(500), "Failed to delete, unexpected error 500")
    }

    "return DeleteConsentFailed for exception" in new TestSetUp {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.failed(new RuntimeException("Network timeout")))

      val result: DeleteConsentResponse = imiConnector.deleteConsent(emailAddress, groupId).futureValue
      result mustBe DeleteConsentFailed(None, "Imi server error Network timeout")
    }
  }

  class TestSetUp {

    val httpClientMock: HttpClientV2 = mock[HttpClientV2]
    val requestBuilder: RequestBuilder = mock[RequestBuilder]

    val jsonResponse: String =
      """
        |    {
        |        "requestTimestamp": "2023-01-06T15:30:11.676Z",
        |        "messageId": "c06bcfad-21ca-4ebd-a805-e220419e9a35",
        |        "correlationId": "ded81d59-9e8a-4806-b5d1-a82ea350b056",
        |        "status": "queued"
        |    }
        |""".stripMargin

    when(
      httpClientMock.post(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(
      httpClientMock.get(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(
      httpClientMock.delete(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(requestBuilder.withBody(any)(using any, any, any)).thenReturn(requestBuilder)
    when(requestBuilder.withProxy).thenReturn(requestBuilder)
    when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)
    when(requestBuilder.execute[HttpResponse](using any, any))
      .thenReturn(Future.successful(HttpResponse(201, jsonResponse)))

    val senderDomainConfiguration: SenderDomainConfiguration =
      new FakeSenderDomainConfiguration {}.senderDomainConfiguration

    val fakeAuditConnector: AuditConnector = new AuditConnector {
      var auditEvents: List[DataEvent] = List.empty

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
    val emailContent = EmailContent(
      Channel.EMAIL,
      "test@hmrc.com",
      List(To(List(EmailAddress("senderEmail@gmail.com")), "correlationId")),
      "",
      Options(false, true, "HMRC"),
      ContactPolicy("", true, true),
      Seq.empty,
      Content("type", "subject", Some(EmailAddress("replayTo@gmail.com")), "text", "html"),
      ""
    )

    val imiConnector =
      new ImiConnector(
        senderDomainConfiguration,
        httpClientMock,
        "key",
        "https://imi:8080",
        "https://imi-consent:8080",
        fakeAuditConnector
      )

    val encryption: Encryption = mock[Encryption]
    val emailAddress: EmailAddress = EmailAddress("test@example.com")
    val groupId = "123456789"

    lazy val mockResponse: HttpResponse = mock[HttpResponse]
  }
}

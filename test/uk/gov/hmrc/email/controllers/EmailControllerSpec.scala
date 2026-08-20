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

package uk.gov.hmrc.email.controllers

import org.apache.pekko.stream.Materializer
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import org.mockito.ArgumentMatchers.any
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.{ Application, Configuration, inject }
import play.api.libs.json.{ JsObject, JsValue, Json }
import play.api.mvc.Result
import play.api.test.Helpers.*
import play.api.test.{ FakeHeaders, FakeRequest, Helpers }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.connectors.{ EmailRendererConnector, TemplateRenderResult }
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.services.{ Router, Routers }
import uk.gov.hmrc.http.HttpReads
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.http.connector.AuditResult.Success

import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class EmailControllerSpec extends SpecBase with ScalaFutures with GuiceOneAppPerTest {

  "return BadRequest when Reply-To-Address used with non gov.uk domain" in new TestCase {
    private val newSendEmailRequest =
      createSendEmailRequest(
        to = List("someone@gmail.com"),
        templateId = "digital_tariffs_advice_request",
        replyToAddress = Some("replyto@gmail.com")
      )

    private val fakeRequest =
      FakeRequest(
        Helpers.POST,
        routes.EmailController.send(defaultSenderDomain).url,
        FakeHeaders(),
        Json.toJson(newSendEmailRequest)
      )

    private val result = emailController.send(defaultSenderDomain)(fakeRequest)

    status(result) must be(BAD_REQUEST)
    (contentAsJson(result) \ "message").get
      .as[String] must be("Cannot use Reply-To-Address when sending to a non gov.uk domain")
  }

  "return BadRequest when templateId is not valid" in new TestCase {
    private val newSendEmailRequest =
      createSendEmailRequest(
        to = List("test@gov.uk"),
        templateId = "newMessageAlert_SA300",
        replyToAddress = Some("replyto@gmail.com")
      )
    private val fakeRequest =
      FakeRequest(
        Helpers.POST,
        routes.EmailController.send(defaultSenderDomain).url,
        FakeHeaders(),
        Json.toJson(newSendEmailRequest)
      )
    private val result = emailController.send(defaultSenderDomain)(fakeRequest)

    status(result) must be(BAD_REQUEST)
    (contentAsJson(result) \ "message").get.as[String] must be("Forbidden use of Reply-To-Address")
  }

  "send" should {
    "return BAD_REQUEST for valid email address but with unknown domain" in new TestCase {
      private val newSendEmailRequest =
        createSendEmailRequest(
          to = List(EmailAddress("test@gov.uk")),
          templateId = "digital_tariffs_advice_request",
          replyToAddress = Some("replyto@gov.uk")
        )

      private val fakeRequest =
        FakeRequest(
          Helpers.POST,
          routes.EmailController.send("unknown domain").url,
          FakeHeaders(),
          Json.toJson(newSendEmailRequest)
        )

      private val result = emailController.send(defaultSenderDomain)(fakeRequest)

      status(result) must be(BAD_REQUEST)
    }

    "return ACCEPTED for a valid domain and valid email address with empty to field" in new TestCase {
      import TemplateRenderResult.templateRenderResultFormat

      val jsonString: String =
        s"""
           | {
           |   "to":["a@gov.uk","b@gov.uk"],
           |   "templateId":"digital_tariffs_advice_request",
           |   "parameters":{
           |     "param1": "value1"
           |   },
           |   "tags":{
           |   "param1": "value1"
           |   },
           |   "force":false,
           |   "replyToAddress":"replyto@gov.uk",
           |   "auditData":{
           |     "param1": "value1"
           |   }
           |}
          """.stripMargin

      val plainText = new String(Base64.getDecoder.decode("test"), StandardCharsets.UTF_8)
      val html = new String(Base64.getDecoder.decode("test"), StandardCharsets.UTF_8)

      private val fakeRequest = FakeRequest(POST, routes.EmailController.send("hmrc").url)
      val templateRenderResultJsonString: String =
        """{
          |"plain":"dGVzdF90aXRsZV90ZXh0",
          |"html":"PGhlYWQ+dGVzdDwvaGVhZD4=",
          |"fromAddress":"test_address",
          |"subject":"test_sub",
          |"service":"test_service",
          |"priority":"standard",
          |"templateId":"test_template_id"
          |}""".stripMargin

      val application: Application = applicationBuilder
        .overrides(
          inject.bind[AuditConnector].toInstance(mockAuditConnector),
          inject.bind[EmailRendererConnector].toInstance(mockEmailRendererConnector),
          inject.bind[Router].toInstance(mockRouter),
          inject.bind[RequestBuilder].toInstance(mockRequestBuilder),
          inject.bind[HttpClientV2].toInstance(mockHttpClientV2)
        )
        .configure(
          "microservice.metrics.enabled" -> false,
          "metrics.enabled"              -> false
        )
        .build()

      when(mockAuditConnector.sendEvent(any)(any, any)).thenReturn(Future.successful(Success))
      when(mockRouter.store(any)(any)).thenReturn(Future.successful(Right(())))

      when(mockRequestBuilder.withBody(any)(using any, any, any)).thenReturn(mockRequestBuilder)

      when(mockRequestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.successful(Json.parse(templateRenderResultJsonString).as[TemplateRenderResult]))

      when(mockHttpClientV2.post(any[URL]())(any())).thenReturn(mockRequestBuilder)

      running(application) {
        val result: Future[Result] = route(application, fakeRequest.withBody(Json.parse(jsonString))).value

        status(result) must be(ACCEPTED)
      }
    }

    "throw error message for invalid Email address" in new TestCase {
      val jsonString: String =
        s"""
           | {
           |   "to":[
           |      "test$$test.com"
           |   ],
           |   "templateId":"newMessageAlert_SA300",
           |   "parameters":{
           |     "param1": "value1"
           |   },
           |   "force":true,
           |   "auditData":{
           |     "param1": "value1"
           |   }
           |}
    """.stripMargin

      val newSendEmailRequest: JsObject = Json.parse(jsonString).as[JsObject]

      private val fakeRequest =
        FakeRequest(
          Helpers.POST,
          routes.EmailController.send(defaultSenderDomain).url,
          FakeHeaders(),
          Json.toJson(newSendEmailRequest)
        )

      private val result = emailController.send(defaultSenderDomain)(fakeRequest)

      status(result) must be(BAD_REQUEST)
      contentAsString(result) must be("""{"reason": "email: not a valid email address"}""")
    }
  }

  "sendTemplatedEmail" should {
    "return bad request" in new TestCase {
      private val newSendEmailRequest =
        createSendEmailRequest(
          to = List(EmailAddress("test@gov.uk")),
          templateId = "digital_tariffs_advice_request",
          replyToAddress = Some("replyto@gov.uk")
        )

      private val fakeRequest: FakeRequest[JsValue] =
        FakeRequest(
          Helpers.POST,
          routes.EmailController.sendTemplatedEmail().url,
          FakeHeaders(),
          Json.toJson(newSendEmailRequest)
        )

      private val result: Future[Result] = emailController.sendTemplatedEmail().apply(fakeRequest)

      status(result) must be(BAD_REQUEST)
    }
  }

  trait TestCase {
    implicit val materializer: Materializer = mock[Materializer]
    val routers: Routers = app.injector.instanceOf[Routers]

    val mockAuditConnector: AuditConnector = mock[AuditConnector]
    val mockConfiguration: Configuration = mock[Configuration]
    val mockEmailRendererConnector: EmailRendererConnector = mock[EmailRendererConnector]
    val mockHttpClientV2: HttpClientV2 = mock[HttpClientV2]
    val mockRequestBuilder: RequestBuilder = mock[RequestBuilder]
    val mockRouter: Router = mock[Router]

    when(mockConfiguration.getOptional[String]("replyToTemplateIds")).thenReturn(Some("digital_tariffs_advice_request"))

    val emailController: EmailController = new EmailController(
      routers,
      mockAuditConnector,
      Helpers.stubControllerComponents(),
      mockConfiguration
    )

    val defaultSenderDomain = "hmrc"

    def createSendEmailRequest(
      to: List[String] = Nil,
      templateId: String,
      force: Boolean = false,
      eventUrl: Option[String] = None,
      onSendUrl: Option[String] = None,
      replyToAddress: Option[String] = None
    ): JsValue = {

      val jsonString =
        s"""
           | {
           |   "to":["${to.head}"],
           |   "templateId":"$templateId",
           |   "parameters":{
           |     "param1": "value1"
           |   },
           |   "force":$force,
           |   "eventUrl":"$eventUrl",
           |   "onSendUrl":"$onSendUrl",
           |   "replyToAddress":"${replyToAddress.get}",
           |   "auditData":{
           |     "param1": "value1"
           |   }
           |}
    """.stripMargin

      Json.parse(jsonString).as[JsObject]
    }
  }
}

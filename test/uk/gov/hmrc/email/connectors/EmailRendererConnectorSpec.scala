/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.controllers.model.SendEmailRequest
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.services.Priority.Priority
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, UpstreamErrorResponse }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import java.net.{ URI, URL }
import scala.{ Option, concurrent }
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

class EmailRendererConnectorSpec extends SpecBase with ScalaFutures with MockitoSugar {

  type Hdrs = Seq[(String, String)]

  "Render template" should {

    "call the /templates/:templateId endpoint of the renderer with the correct parameters" in new TestCase {
      when(requestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.successful(responseFromRenderer))

      val parameters: Map[String, String] =
        Map[String, String]("first-parameter" -> "first-value", "second-parameter" -> "second-value")

      val result = connector.render("some-template-id", parameters, List.empty).futureValue
      assert(result == Right((None, RenderResult("", "", "", "", "one-supported-service", Some("some-template-id")))))
      verify(mockHttpClient, times(1)).post(URI.create("https://some-host:1234/templates/some-template-id").toURL)

    }

    "call /templates/:templateId endpoint with email parameter" in new TestCase {

      when(requestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.successful(responseFromRenderer))

      val parameters: Map[String, String] =
        Map[String, String]("first-parameter" -> "first-value", "second-parameter" -> "second-value")

      val result = connector
        .render("some-template-id", parameters, List(EmailAddress("test@test.com")))
        .futureValue

      assert(result == Right((None, RenderResult("", "", "", "", "one-supported-service", Some("some-template-id")))))
      verify(mockHttpClient, times(1)).post(URI.create("https://some-host:1234/templates/some-template-id").toURL)

    }

    "return render result if the request is valid and the template exists" in new TestCase {

      when(requestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.successful(responseFromRenderer))

      val actualResponse: Either[ErrorMessage, (Option[Priority], RenderResult)] =
        connector.render("some-template-id", Map.empty, List.empty).futureValue
      actualResponse.isRight mustBe true

      val (priority, renderResult) = actualResponse match {
        case Right((p, rr)) => (p, rr)
        case _              => fail()
      }
      priority mustBe empty
      renderResult mustBe responseFromConnector
    }

    "return error message if templateId does not exist" in new TestCase {
      when(requestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.failed(UpstreamErrorResponse("some-message", 404)))

      val actualResponse: Either[ErrorMessage, (Option[Priority], RenderResult)] =
        connector.render("some-template-id", Map.empty, List.empty).futureValue

      actualResponse.isLeft mustBe true
      actualResponse.left.toOption.get.reason mustBe s"Template some-template-id does not exist"
    }

    "return error message if request is malformed" in new TestCase {
      when(requestBuilder.execute(using any[HttpReads[TemplateRenderResult]], any[ExecutionContext]))
        .thenReturn(Future.failed(UpstreamErrorResponse("some-badRequest-errorMessage", 400)))

      val actualResponse: Either[ErrorMessage, (Option[Priority], RenderResult)] =
        connector.render("some-template-id", Map.empty, List.empty).futureValue

      actualResponse.isLeft mustBe true
      actualResponse.left.toOption.get mustBe ErrorMessage.fromExceptionReason("some-badRequest-errorMessage")
    }
  }

  "takeOnlyIfOneEmail" should {
    "return email if there is only one email" in new TestCase {
      connector.takeOnlyIfOneEmail(List(EmailAddress("test@test.com"))) mustBe Some("test@test.com")
      connector.takeOnlyIfOneEmail(List(EmailAddress("test@test.com"), EmailAddress("test@test.com"))) mustBe None
      connector.takeOnlyIfOneEmail(List.empty) mustBe None
    }
  }

  trait TestCase {
    implicit val ec: ExecutionContext = ExecutionContext.global

    val mockHttpClient: HttpClientV2 = mock[HttpClientV2]
    val requestBuilder: RequestBuilder = mock[RequestBuilder]
    val mockServicesConfig: ServicesConfig = new ServicesConfig(
      Configuration(
        "microservice.services.some-renderer.host"     -> "some-host",
        "microservice.services.some-renderer.port"     -> 1234,
        "microservice.services.some-renderer.protocol" -> "https"
      )
    )

    when(mockHttpClient.post(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
    when(requestBuilder.withBody(any)(using any, any, any)).thenReturn(requestBuilder)

    implicit val headerCarrier: HeaderCarrier = HeaderCarrier()

    implicit val patienceConfig: PatienceConfig =
      PatienceConfig(
        timeout = scaled(Span(60, Seconds)),
        interval = scaled(Span(150, Millis))
      )

    val responseFromRenderer: TemplateRenderResult =
      TemplateRenderResult("", "", "", "", "one-supported-service", None, Some("some-template-id"))
    val responseFromConnector: RenderResult =
      RenderResult("", "", "", "", "one-supported-service", Some("some-template-id"))

    val connector: EmailRendererConnector =
      new EmailRendererConnector("some-renderer", mockServicesConfig, mockHttpClient)

    def requestFor(templateId: String, parameters: Map[String, String]): SendEmailRequest =
      SendEmailRequest(List.empty, templateId, parameters, Map.empty, force = true, None, None, Map.empty)
  }
}

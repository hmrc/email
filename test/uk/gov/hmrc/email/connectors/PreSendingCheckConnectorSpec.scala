/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }

import java.net.{ URL, UnknownHostException }
import scala.concurrent.{ ExecutionContext, Future }

class PreSendingCheckConnectorSpec extends SpecBase with ScalaFutures with MockitoSugar {
  val httpClientMock: HttpClientV2 = mock[HttpClientV2]
  val requestBuilder: RequestBuilder = mock[RequestBuilder]

  implicit val hc: HeaderCarrier = new HeaderCarrier()
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  when(
    httpClientMock.get(any[URL])(any[HeaderCarrier])
  ).thenReturn(requestBuilder)

  val connector = new PreSendingCheckConnector(httpClientMock)

  "shouldISend" must {
    "return the success response for sendAlert" in {
      when(requestBuilder.execute[SendAlertResponse](using any, any))
        .thenReturn(Future.successful(SendAlertResponse(true)))
      val response = connector.shouldISend("https://event-hub.protected.mdtp:443/").futureValue
      response mustBe Right(SendAlertResponse(true))
    }

    "return 'false' as left when call-back url is for secure-message " in {
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(Future.successful(HttpResponse(404, "")))
      val response = connector.shouldISend("https://secure-message.protected.mdtp:443/").futureValue
      response mustBe Left(false)
    }

    "return 'true' as left when api call with call-back url produces UnknownHostException" in {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.failed(UnknownHostException("error occurred")))

      val response = connector.shouldISend("https://secure-message.protected.mdtp:443/").futureValue

      response mustBe Left(true)
    }
  }

  "SendAlertResponse.formats" must {
    import SendAlertResponse.formats

    "read the json correctly" in new Setup {
      Json.parse(sendAlertResponseJsonString).as[SendAlertResponse] mustBe sendAlertResponse
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(sendAlertResponseInvalidJsonString).as[SendAlertResponse]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(sendAlertResponse) mustBe Json.parse(sendAlertResponseJsonString)
    }
  }

  trait Setup {
    val sendAlertResponse: SendAlertResponse = SendAlertResponse(sendAlert = true)

    val sendAlertResponseJsonString: String = """{"sendAlert":true}""".stripMargin
    val sendAlertResponseInvalidJsonString: String = """{"unknown":true}""".stripMargin
  }
}

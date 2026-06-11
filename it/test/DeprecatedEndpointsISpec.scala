/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.{ WSResponse, writeableOf_JsValue }
import test.TestConfig
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.client.HttpClientV2
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

class DeprecatedEndpointsISpec extends PlaySpec with EmailBaseISpec with ResponseMatchers {

  override lazy val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
  val sampleValidEmail = "valid@mail.com"

  override def additionalConfig: Map[String, ?] =
    TestConfig.services

  def `be valid` =
    have(status(Status.OK)) and have(jsonContent("""{ "valid": true }"""))

  "The deprecated send-templated-email endpoint" should {

    val exampleParameterValue: String =
      "http://www.hmrc.co.uk/verify/SOMEOBJECTID"

    def request: String =
      s"""{
         |"to":["a@b.com"],
         |"templateId":"verifyEmailAddress",
         |"parameters":{"verificationLink":"$exampleParameterValue"}
         |}
          """.stripMargin

    "get back a 200 when called" in {
      httpClient
        .post(resource(s"/send-templated-email"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe Status.ACCEPTED
    }
  }

}

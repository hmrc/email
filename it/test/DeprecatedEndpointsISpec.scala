/*
 * Copyright 2020 HM Revenue & Customs
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

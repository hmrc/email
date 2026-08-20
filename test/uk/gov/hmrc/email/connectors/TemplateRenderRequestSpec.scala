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

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_ADDRESS_VALUE, TEST_PARAMETERS_MAP }

class TemplateRenderRequestSpec extends SpecBase {

  "Json Reads" should {
    import TemplateRenderRequest.templateRenderRequestFormat

    "read the json correctly" in new Setup {
      Json.parse(templateRenderRequestJsonString).as[TemplateRenderRequest] mustBe templateRenderRequest
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(templateRenderRequestInvalidJsonString).as[TemplateRenderRequest]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(templateRenderRequest) mustBe Json.parse(templateRenderRequestJsonString)
    }
  }

  trait Setup {
    val templateRenderRequest: TemplateRenderRequest =
      TemplateRenderRequest(parameters = TEST_PARAMETERS_MAP, email = Some(TEST_EMAIL_ADDRESS_VALUE))

    val templateRenderRequestJsonString: String =
      """{"parameters":{"test_key":"test_value"},"email":"test@test.com"}""".stripMargin

    val templateRenderRequestInvalidJsonString: String = """{"email":"test@test.com"}""".stripMargin
  }
}

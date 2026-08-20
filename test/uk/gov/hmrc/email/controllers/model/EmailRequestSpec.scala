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

package uk.gov.hmrc.email.controllers.model

import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.TEST_EMAIL
import play.api.libs.json.{ JsResultException, Json }

class EmailRequestSpec extends SpecBase {

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(emailRequestJson).as[EmailRequest] mustBe emailRequest
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(emailRequestInvalidJson).as[EmailRequest]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(emailRequest) mustBe Json.parse(emailRequestJson)
    }
  }

  trait Setup {
    val emailRequest: EmailRequest = EmailRequest(TEST_EMAIL)

    val emailRequestJson: String = """{"email":"test@test.com"}""".stripMargin
    val emailRequestInvalidJson: String = """{"email":5}""".stripMargin
  }
}

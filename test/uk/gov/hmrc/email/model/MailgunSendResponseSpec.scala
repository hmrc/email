/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase

class MailgunSendResponseSpec extends SpecBase {

  "MailgunSendResponse" should {
    import MailgunSendResponse.formats

    "read from JSON" in new Setup {
      Json.parse(mailgunSendResponseJsonString).as[MailgunSendResponse] mustBe mailgunSendResponse
    }

    "throw exception for the invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(invalidJsonString).as[MailgunSendResponse]
      }
    }

    "write to Json" in new Setup {
      Json.toJson(mailgunSendResponse) mustBe Json.parse(mailgunSendResponseJsonString)
    }
  }

  trait Setup {
    val mailgunSendResponse: MailgunSendResponse = MailgunSendResponse(MailgunId("id"), "Got it, thanks")

    val mailgunSendResponseJsonString: String =
      """
        |{
        | "id": "id",
        | "message": "Got it, thanks"
        |}""".stripMargin

    val invalidJsonString: String = """{"message":"Got it, thanks"}""".stripMargin
  }
}

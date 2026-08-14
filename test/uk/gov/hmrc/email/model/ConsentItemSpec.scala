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
import uk.gov.hmrc.email.TestData.{ TEST_CHANNEL, TEST_FROM_ADDRESS, TEST_REASON, TEST_TIME_INSTANT }

class ConsentItemSpec extends SpecBase {

  "Json Reads" should {
    import ConsentItem.reads

    "read the json correctly" in new Setup {
      Json.parse(consentItemJsonString).as[ConsentItem] mustBe consentItem
    }

    "throw exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(consentItemInvalidJsonString).as[ConsentItem]
      }.errors.head._2.head.messages.head must be("ConsentItem lastUpdated date has incorrect format")
    }
  }

  trait Setup {
    val consentItem: ConsentItem = ConsentItem(
      channel = TEST_CHANNEL,
      address = TEST_FROM_ADDRESS,
      consent = true,
      reason = TEST_REASON,
      lastUpdated = TEST_TIME_INSTANT
    )

    val consentItemJsonString: String =
      """{
        |"channel":"test_channel",
        |"address":"test_address",
        |"consent":true,
        |"reason":"test_reason",
        |"lastUpdated":"1970-01-01T18:11:18.234Z"
        |}""".stripMargin

    val consentItemInvalidJsonString: String =
      """{
        |"channel":"test_channel",
        |"address":"test_address",
        |"consent":true,
        |"reason":"test_reason",
        |"lastUpdated":2025
        |}""".stripMargin
  }
}

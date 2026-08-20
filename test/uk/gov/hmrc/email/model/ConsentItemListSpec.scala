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

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_CHANNEL, TEST_FROM_ADDRESS, TEST_REASON, TEST_TIME_INSTANT, TEST_TOKEN }

class ConsentItemListSpec extends SpecBase {
  import ConsentItemList.reads

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(consentItemListJsonString).as[ConsentItemList] mustBe consentItemList
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(consentItemListInvalidJsonString).as[ConsentItemList]
      }
    }
  }

  trait Setup {
    val consentItem: ConsentItem =
      ConsentItem(
        channel = TEST_CHANNEL,
        address = TEST_FROM_ADDRESS,
        consent = true,
        reason = TEST_REASON,
        lastUpdated = TEST_TIME_INSTANT
      )

    val consentItemList: ConsentItemList = ConsentItemList(items = List(consentItem), continueToken = Some(TEST_TOKEN))

    val consentItemListJsonString: String =
      """{
        |"items":[
        |{
        |"channel":"test_channel",
        |"address":"test_address",
        |"consent":true,
        |"reason":"test_reason",
        |"lastUpdated":"1970-01-01T18:11:18.234Z"
        |}
        |],
        |"continueToken":"12357890"}""".stripMargin

    val consentItemListInvalidJsonString: String = """{}""".stripMargin
  }
}

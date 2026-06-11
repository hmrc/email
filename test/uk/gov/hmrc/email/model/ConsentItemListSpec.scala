/*
 * Copyright 2025 HM Revenue & Customs
 *
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

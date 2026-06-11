/*
 * Copyright 2025 HM Revenue & Customs
 *
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

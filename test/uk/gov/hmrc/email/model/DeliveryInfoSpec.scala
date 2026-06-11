/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_CHANNEL, TEST_DESCRIPTION, TEST_DESTINATION_TYPE, TEST_EMAIL_ADDRESS_VALUE, TEST_INFO, TEST_LOCAL_DATETIME }
import uk.gov.hmrc.email.model.DeliveryStatus.Delivered

class DeliveryInfoSpec extends SpecBase {

  "Json Reads" should {
    import DeliveryInfo.formatReads

    "read the json correctly" in new Setup {
      Json.parse(deliveryInfoJsonString1).as[DeliveryInfo] mustBe deliveryInfo
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(deliveryInfoInvalidJsonString).as[DeliveryInfo]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(deliveryInfo) mustBe Json.parse(deliveryInfoJsonString)
    }
  }

  trait Setup {
    val deliveryInfo: DeliveryInfo = DeliveryInfo(
      timeStamp = TEST_LOCAL_DATETIME,
      description = TEST_DESCRIPTION,
      code = "1",
      deliveryChannel = TEST_CHANNEL,
      additionalInfo = TEST_INFO,
      destination = TEST_EMAIL_ADDRESS_VALUE,
      destinationType = TEST_DESTINATION_TYPE,
      deliveryStatus = Delivered
    )

    val deliveryInfoJsonString: String =
      """{
        |"timeStamp":"2025-12-06T11:30:50",
        |"description":"test_description",
        |"code":"1",
        |"deliveryChannel":"test_channel",
        |"additionalInfo":"test_info",
        |"destination":"test@test.com",
        |"destinationType":"email",
        |"deliveryStatus":"Delivered"
        |}""".stripMargin

    val deliveryInfoJsonString1: String =
      """{
        |"timeStamp":"2025-12-06T11:30:50",
        |"Description":"test_description",
        |"code":"1",
        |"deliveryChannel":"test_channel",
        |"additionalInfo":"test_info",
        |"destination":"test@test.com",
        |"destinationType":"email",
        |"deliveryStatus":"Delivered"
        |}""".stripMargin

    val deliveryInfoInvalidJsonString: String =
      """{
        |"description":"test_description",
        |"code":"1",
        |"deliveryChannel":"test_channel",
        |"additionalInfo":"test_info",
        |"destination":"test@test.com",
        |"destinationType":"email",
        |"deliveryStatus":"Delivered"
        |}""".stripMargin
  }
}

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
import uk.gov.hmrc.email.TestData.{ TEST_CHANNEL, TEST_DESCRIPTION, TEST_DESTINATION_TYPE, TEST_EMAIL_ADDRESS_VALUE, TEST_INFO, TEST_LOCAL_DATETIME, TEST_RANDOM_UUID, TEST_SUBJECT }
import uk.gov.hmrc.email.model.DeliveryStatus.Delivered

class EventPayloadSpec extends SpecBase {

  "Json Reads" should {
    import EventPayload.reads

    "read the json correctly" in new Setup {
      Json.parse(eventPayloadJsonString).as[EventPayload] mustBe eventPayload
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventPayloadInvalidJsonString).as[EventPayload]
      }
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

    val deliveryInfoNotification: DeliveryInfoNotification = DeliveryInfoNotification(
      deliveryInfo = deliveryInfo,
      subtId = TEST_SUBJECT,
      transId = TEST_RANDOM_UUID,
      callbackData = Map("test_key" -> "test_value"),
      correlationId = TEST_RANDOM_UUID
    )

    val rawEvent: RawEvent = RawEvent(deliveryInfoNotification)

    val eventPayload: EventPayload = EventPayload(rawEvent)

    val eventPayloadJsonString: String =
      """{
        |"df_payload":{
        |"deliveryInfoNotification":{
        |"deliveryInfo":{
        |"timeStamp":"2025-12-06T11:30:50",
        |"Description":"test_description",
        |"code":"1",
        |"deliveryChannel":"test_channel",
        |"additionalInfo":"test_info",
        |"destination":"test@test.com",
        |"destinationType":"email",
        |"deliveryStatus":"Delivered"
        |},
        |"subtid":"test_sub",
        |"transid":"00000000-0000-0000-0000-000000000000",
        |"callbackData":"eyJ0ZXN0X2tleSI6InRlc3RfdmFsdWUifQ==",
        |"correlationid":"00000000-0000-0000-0000-000000000000"
        |}
        |}
        |}""".stripMargin

    val eventPayloadInvalidJsonString: String =
      """{
        |"df_payload":{
        |"deliveryInfoNotification":{
        |"deliveryInfo":{
        |"timeStamp":"2025-12-06T11:30:50",
        |"description":"test_description",
        |"code":"1",
        |"deliveryChannel":"test_channel",
        |"additionalInfo":"test_info",
        |"destination":"test@test.com",
        |"destinationType":"email",
        |"deliveryStatus":"Delivered"
        |},
        |"transId":"00000000-0000-0000-0000-000000000000",
        |"callbackData":{"test_key":"test_value"},
        |"correlationId":"00000000-0000-0000-0000-000000000000"
        |}
        |}
        |}""".stripMargin
  }
}

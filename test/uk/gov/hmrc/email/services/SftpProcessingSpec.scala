/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import play.api.libs.json.Json
import play.api.test.Helpers.*
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.model.EventPayload

class SftpProcessingSpec extends SpecBase {

  "SftpProcessing" must {
    "parse single df_payload" in {
      val raw =
        """[
          |  {
          |    "df_payload": {
          |      "deliveryInfoNotification": {
          |        "deliveryInfo": {
          |          "timeStamp": "2023-06-26T12:30:58.997+01:00",
          |          "Description": "Delivered",
          |          "code": "7500",
          |          "deliveryChannel": "email",
          |          "additionalInfo": "",
          |          "destination": "chandan.ray+345@digital.hmrc.gov.uk",
          |          "destinationType": "email",
          |          "deliveryStatus": "Delivered"
          |        },
          |        "subtid": "",
          |        "transid": "726e1432-0b04-47b0-8c58-d0ee981fa6aa",
          |        "callbackData": "eyJyZWdpbWUiOiJ6dFpBbG5EWTJxVVRORmVCRDZtM01RPT0iLCJ0ZW1wbGF0ZUlkIjoidWRBTEp5SWJLYXBJLzdkMkhoaDlYT20yblJLeHJQMVpRcUdYTEFTUUw4bz0iLCJwbGF0Zm9ybSI6IjRQUmlxc3dqN3JxdGpoRVMzUFdjZGc9PSIsIkNvbnRhY3RQb2xpY3lHcm91cElkIjoieWs3X2hNOGVRUWVGd0Ezekh5ZlJnZyJ9",
          |        "correlationid": "36bf0ab6-e5b8-4387-99fd-9e57105a86a8"
          |      }
          |    },
          |    "df_status": "2",
          |    "df_status_text": "HTTP Status 429",
          |    "df_response_status": "429"
          |  }
          |
          |]""".stripMargin

      val events: Seq[EventPayload] = Json.parse(raw).as[Seq[EventPayload]]

      events mustBe events
    }

    "parse multiple df_payload" in {
      val raw =
        """[
          |  {
          |    "df_payload": {
          |      "deliveryInfoNotification": {
          |        "deliveryInfo": {
          |          "timeStamp": "2023-06-26T12:30:58.997+01:00",
          |          "Description": "Delivered",
          |          "code": "7500",
          |          "deliveryChannel": "email",
          |          "additionalInfo": "",
          |          "destination": "chandan.ray+345@digital.hmrc.gov.uk",
          |          "destinationType": "email",
          |          "deliveryStatus": "Delivered"
          |        },
          |        "subtid": "",
          |        "transid": "726e1432-0b04-47b0-8c58-d0ee981fa6aa",
          |        "callbackData": "eyJyZWdpbWUiOiJ6dFpBbG5EWTJxVVRORmVCRDZtM01RPT0iLCJ0ZW1wbGF0ZUlkIjoidWRBTEp5SWJLYXBJLzdkMkhoaDlYT20yblJLeHJQMVpRcUdYTEFTUUw4bz0iLCJwbGF0Zm9ybSI6IjRQUmlxc3dqN3JxdGpoRVMzUFdjZGc9PSIsIkNvbnRhY3RQb2xpY3lHcm91cElkIjoieWs3X2hNOGVRUWVGd0Ezekh5ZlJnZyJ9",
          |        "correlationid": "36bf0ab6-e5b8-4387-99fd-9e57105a86a8"
          |      }
          |    },
          |    "df_status": "2",
          |    "df_status_text": "HTTP Status 429",
          |    "df_response_status": "429"
          |  },
          |  {
          |    "df_payload": {
          |      "deliveryInfoNotification": {
          |        "deliveryInfo": {
          |          "timeStamp": "2023-06-30T07:39:09.567+01:00",
          |          "code": "9002",
          |          "Description": "Recipient has not consented to message",
          |          "deliveryChannel": "email",
          |          "destination": "test.platform@digital.hmrc.gov.uk",
          |          "additionalInfo": "",
          |          "destinationType": "email",
          |          "deliveryStatus": "Failed"
          |        },
          |        "subtid": "",
          |        "transid": "1f8f0166-9318-458a-8096-b964a2d008a3",
          |        "callbackData": "eyJyZWdpbWUiOiJWZ0lkK0ZXNkdwaGl4bGw1MElsTnhnPT0iLCJ0ZW1wbGF0ZUlkIjoiVklIZk1IaERzRVMxeHlWSWtXSUNrTGZJMWVOK2hIR3RVNVppUEJBRmYyST0iLCJwbGF0Zm9ybSI6IjRQUmlxc3dqN3JxdGpoRVMzUFdjZGc9PSIsIkNvbnRhY3RQb2xpY3lHcm91cElkIjoieWs3X2hNOGVRUWVGd0Ezekh5ZlJnZyJ9",
          |        "correlationid": "459c988b-3bae-4398-8cdc-23f1891c3499"
          |      }
          |    },
          |    "df_status": "1",
          |    "df_status_text": "HTTP Status 503",
          |    "df_response_status": "503",
          |    "df_response_content": "b'{ \"code\": \"SERVER_ERROR\", \"message\": \"The \\'misc/email-events\\' API is currently unavailable\" }'"
          |  }
          |]""".stripMargin

      val events: Seq[EventPayload] = Json.parse(raw).as[Seq[EventPayload]]

      events mustBe events
    }

    "process files present in the provided directory" in {

      val sftpProcessing = applicationBuilder.build().injector.instanceOf[SftpProcessing]

      val result: Int = await(sftpProcessing.processFiles)

      result must be(1)
    }
  }
}

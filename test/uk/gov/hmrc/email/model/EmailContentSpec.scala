/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.TEST_GROUP_ID
import uk.gov.hmrc.email.emailaddress.EmailAddress

import java.util.UUID

class EmailContentSpec extends SpecBase {

  "EmailContent" must {
    "parse to json" in {

      val correlationId = UUID.randomUUID().toString
      val emailContent = EmailContent(
        Channel.EMAIL,
        "test@test-domain.co.uk",
        List(To(List(EmailAddress("test@digital.hmrc.gov.uk")), correlationId)),
        "callbackData",
        Options(false, true, "HMRC digital"),
        ContactPolicy("XXX", true, true),
        Seq("submitted"),
        Content(
          "html",
          "Self assessment reminder",
          Some(EmailAddress("test@digital-contact.co.uk")),
          "\nThis is a test email only. Should you receive this email please ignore it",
          "\n\n<!DOCTYPE html>\n<html>\n  <head>\n </head>\n <body>This is a test email only. Should you receive this email please ignore it</body>\n</html>\n"
        ),
        "https://hooks.uk.webexconnect.io/events/WFMW85P271"
      )

      Json.toJson(emailContent) mustBe Json.parse(
        s"""
           |{
           |"channel":"email",
           |"from":"test@test-domain.co.uk",
           |    "to":[
           |      {
           |        "email":[
           |          "test@digital.hmrc.gov.uk"
           |        ],
           |        "correlationId":"$correlationId"
           |      }
           |    ],
           |    "callbackData":"callbackData",
           |"options":{
           |    "trackClicks":false,
           |    "trackOpens":true,
           |    "fromName":"HMRC digital"
           |  },
           |"contactPolicy":
           |   {
           |     "contactPolicyGroup" : "XXX",
           |     "channelCheckConsent" : true,
           |     "channelApplyFrequencyCap" : true
           |   },
           |"requestedReceipts":["submitted"],
           |"content":{
           |    "type":"html",
           |    "subject":"Self assessment reminder",
           |    "replyTo":"test@digital-contact.co.uk",
           |    "text":"\\nThis is a test email only. Should you receive this email please ignore it",
           |    "html":"\\n\\n<!DOCTYPE html>\\n<html>\\n  <head>\\n </head>\\n <body>This is a test email only. Should you receive this email please ignore it</body>\\n</html>\\n"
           |  },
           |   "notifyUrl":"https://hooks.uk.webexconnect.io/events/WFMW85P271"
           |}
           |""".stripMargin
      )

    }
  }

  "ContactPolicy.format" should {
    import ContactPolicy.format

    "read the json correctly" in new Setup {
      Json.parse(contactPolicyJsonString).as[ContactPolicy] mustBe contactPolicy
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(contactPolicyInvalidJsonString).as[ContactPolicy]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(contactPolicy) mustBe Json.parse(contactPolicyJsonString)
    }
  }

  trait Setup {
    val contactPolicy: ContactPolicy =
      ContactPolicy(contactPolicyGroup = TEST_GROUP_ID, channelCheckConsent = true, channelApplyFrequencyCap = true)

    val contactPolicyJsonString: String =
      """{
        |"contactPolicyGroup":"test_group_id",
        |"channelCheckConsent":true,
        |"channelApplyFrequencyCap":true
        |}""".stripMargin

    val contactPolicyInvalidJsonString: String =
      """{
        |"channelCheckConsent":true,
        |"channelApplyFrequencyCap":true
        |}""".stripMargin
  }
}

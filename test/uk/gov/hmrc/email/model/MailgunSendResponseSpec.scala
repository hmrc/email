/*
 * Copyright 2025 HM Revenue & Customs
 *
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

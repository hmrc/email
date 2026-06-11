/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.mailgun

import play.api.libs.json.Json
import play.api.libs.json.JsResultException
import uk.gov.hmrc.email.SpecBase

class EmailAddressValidationResponseSpec extends SpecBase {

  "Json Reads" should {
    import EmailAddressValidationResponse.emailFormat

    "read the json correctly" in new Setup {
      Json
        .parse(emailAddressValidationResponseJsonString)
        .as[EmailAddressValidationResponse] mustBe emailAddressValidationResponse
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json
          .parse(emailAddressValidationResponseInvalidJsonString)
          .as[EmailAddressValidationResponse]
      }
    }
  }

  "Json Writes" should {
    "the object correctly" in new Setup {
      Json.toJson(emailAddressValidationResponse) mustBe Json.parse(emailAddressValidationResponseJsonString)
    }
  }

  trait Setup {
    val emailAddressValidationResponse: EmailAddressValidationResponse = EmailAddressValidationResponse(true)

    val emailAddressValidationResponseJsonString = """{"is_valid":true}"""
    val emailAddressValidationResponseInvalidJsonString = """{"is_valid":"true"}"""
  }
}

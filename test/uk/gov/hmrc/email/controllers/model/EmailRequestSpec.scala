/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.controllers.model

import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.TEST_EMAIL
import play.api.libs.json.{ JsResultException, Json }

class EmailRequestSpec extends SpecBase {

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(emailRequestJson).as[EmailRequest] mustBe emailRequest
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(emailRequestInvalidJson).as[EmailRequest]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(emailRequest) mustBe Json.parse(emailRequestJson)
    }
  }

  trait Setup {
    val emailRequest: EmailRequest = EmailRequest(TEST_EMAIL)

    val emailRequestJson: String = """{"email":"test@test.com"}""".stripMargin
    val emailRequestInvalidJson: String = """{"email":5}""".stripMargin
  }
}

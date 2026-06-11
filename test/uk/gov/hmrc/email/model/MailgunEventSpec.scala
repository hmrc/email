/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.TEST_ENROLMENT

class MailgunEventSpec extends SpecBase {

  "Enrolment.enrolmentReads" should {
    import Enrolment.enrolmentReads

    "read the json correctly" in new Setup {
      Json.parse(enrolmentJsonString).as[Enrolment] mustBe enrolment
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(enrolmentInvalidJsonString).as[Enrolment]
      }
    }
  }

  trait Setup {
    val enrolment: Enrolment = Enrolment(enrolment = Some(TEST_ENROLMENT))

    val enrolmentJsonString = """{"enrolment":"HMRC-CUS-ORG"}"""
    val enrolmentInvalidJsonString = """{"enrolment":1234}"""
  }
}

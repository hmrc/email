/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.scalacheck.Gen
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import uk.gov.hmrc.email.SpecBase

class ErrorMessageSpec extends SpecBase with ScalaCheckDrivenPropertyChecks {

  private val nonEmptyGen = Gen.alphaStr.suchThat(_.nonEmpty)

  "fromExceptionMessage" should {
    "create an ErrorMessage instance with the text in the response body JSON" in forAll(nonEmptyGen) { (text: String) =>
      val json =
        s"""{ "status": "Rendering of template failed", "reason": "$text"}"""
      val errorMessage = s" Response body '$json'"
      ErrorMessage.fromExceptionReason(errorMessage) mustBe ErrorMessage(text)
    }

    "yield a new ErrorMessage instance when no match occurs" in forAll { (text: String) =>
      ErrorMessage.fromExceptionReason(text) mustBe ErrorMessage(text)
    }
  }

}

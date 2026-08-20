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

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

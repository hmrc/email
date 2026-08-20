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

package uk.gov.hmrc.email.util

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsSuccess, Json }
import uk.gov.hmrc.email.TestData.EMPTY_STRING
import uk.gov.hmrc.email.controllers.util.{ SendEmailRequestValidator, ValidationException }

class SendEmailRequestValidatorSpec extends PlaySpec {

  "jsonAlertQueueReads" should {
    "validate valid alertQueue" in {
      val validAlertQueueJson = Json.toJson("DEFAULT")
      val result = validAlertQueueJson.validate(SendEmailRequestValidator.jsonAlertQueueReads)
      result mustBe a[JsSuccess[?]]
    }

    "throw ValidationException" when {
      import SendEmailRequestValidator.jsonAlertQueueReads

      "alertQueue is empty" in {
        intercept[ValidationException] {
          Json.toJson(EMPTY_STRING).validate(jsonAlertQueueReads)
        }.getMessage mustBe "alertQueue: invalid alert queue provided"
      }

      "alertQueue is invalid" in {
        intercept[ValidationException] {
          Json.toJson("unknown").validate(jsonAlertQueueReads)
        }.getMessage mustBe "alertQueue: invalid alert queue provided"
      }
    }
  }

  "checkValidAlertQueue" should {
    "check valid alertQueue" in {
      val validAlertQueue = Some("DEFAULT")
      val result = SendEmailRequestValidator.checkValidAlertQueue(validAlertQueue)
      result mustBe true
    }

    "check invalid alertQueue" in {
      val invalidAlertQueue = Some("INVALID_QUEUE")
      val result = SendEmailRequestValidator.checkValidAlertQueue(invalidAlertQueue)
      result mustBe false
    }
  }

  "checkEmptyAlertQueue" should {
    "check empty alertQueue" in {
      val emptyAlertQueue = Some(EMPTY_STRING)
      val result = SendEmailRequestValidator.checkEmptyAlertQueue(emptyAlertQueue)
      result mustBe true
    }

    "check non-empty alertQueue" in {
      val nonEmptyAlertQueue = Some("DEFAULT")
      val result = SendEmailRequestValidator.checkEmptyAlertQueue(nonEmptyAlertQueue)
      result mustBe false
    }
  }
}

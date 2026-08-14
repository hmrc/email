/*
 * Copyright 2025 HM Revenue & Customs
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

class EmailQueueProcessingResultsSpec extends SpecBase {
  import EmailQueueProcessingResults.formats

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(emailQueueProcessingResultsJson).as[EmailQueueProcessingResults] mustBe emailQueueProcessingResults
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(emailQueueProcessingResultsInvalidJson).as[EmailQueueProcessingResults]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(emailQueueProcessingResults) mustBe Json.parse(emailQueueProcessingResultsJson)
    }
  }

  trait Setup {
    val emailQueueProcessingResults: EmailQueueProcessingResults =
      EmailQueueProcessingResults(sent = 1, requeued = 1, permanentlyFailed = 1, aborted = 1)

    val emailQueueProcessingResultsJson: String =
      """{
        |"sent":1,
        |"requeued":1,
        |"permanentlyFailed":1,
        |"aborted":1
        |}""".stripMargin

    val emailQueueProcessingResultsInvalidJson: String =
      """{
        |"requeued":1,
        |"permanentlyFailed":1,
        |"aborted":1
        |}""".stripMargin
  }
}

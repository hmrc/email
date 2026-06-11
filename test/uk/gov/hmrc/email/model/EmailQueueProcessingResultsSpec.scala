/*
 * Copyright 2025 HM Revenue & Customs
 *
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

/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_ADDRESS, TEST_FROM_ADDRESS, TEST_HTML, TEST_PARAMETERS_MAP, TEST_PLAIN_TEXT, TEST_SUBJECT, TEST_TEMPLATE_ID, TEST_TEMPLATE_REGIME, TEST_URL }
import uk.gov.hmrc.email.model.RenderResult

class QueuedEmailRequestSpec extends SpecBase {
  "Json Reads" should {
    import QueuedEmailRequest.format

    "read the json correctly" in new Setup {
      Json.parse(queuedEmailRequestJsonString).as[QueuedEmailRequest] mustBe queuedEmailRequest
    }

    "throw exception for the invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(queuedEmailRequestInvalidJsonString).as[QueuedEmailRequest]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(queuedEmailRequest) mustBe Json.parse(queuedEmailRequestJsonString)
    }
  }

  trait Setup {
    val renderedResult: RenderResult =
      RenderResult(
        TEST_PLAIN_TEXT,
        TEST_HTML,
        TEST_FROM_ADDRESS,
        TEST_SUBJECT,
        TEST_TEMPLATE_REGIME,
        Some(TEST_TEMPLATE_ID)
      )

    val queuedEmailRequest: QueuedEmailRequest = QueuedEmailRequest(
      to = List(TEST_EMAIL_ADDRESS),
      templateId = TEST_TEMPLATE_ID,
      parameters = TEST_PARAMETERS_MAP,
      tags = Map.empty,
      force = true,
      eventUrl = Some(TEST_URL),
      onSendUrl = Some(TEST_URL),
      auditData = Map.empty,
      renderedEmail = Some(renderedResult)
    )

    val queuedEmailRequestJsonString: String =
      """{
        |"to":["test@test.com"],
        |"templateId":"test_template_id",
        |"parameters":{"test_key":"test_value"},
        |"tags":{},
        |"force":true,
        |"eventUrl":"www.test.com",
        |"onSendUrl":"www.test.com",
        |"auditData":{},
        |"renderedEmail":{
        |"plain":"test_title_text",
        |"html":"<head>test</head>",
        |"fromAddress":"test_address",
        |"subject":"test_sub",
        |"templateRegime":"test_generic",
        |"templateId":"test_template_id"
        |}
        |}""".stripMargin

    val queuedEmailRequestInvalidJsonString: String =
      """{
        |"to":["test@test.com"],
        |"parameters":{"test_key":"test_value"},
        |"tags":{},
        |"force":true,
        |"eventUrl":"www.test.com",
        |"onSendUrl":"www.test.com",
        |"auditData":{},
        |"renderedEmail":{
        |"plain":"test_title_text",
        |"html":"<head>test</head>",
        |"fromAddress":"test_address",
        |"subject":"test_sub",
        |"templateRegime":"test_generic",
        |"templateId":"test_template_id"
        |}
        |}""".stripMargin
  }
}

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

import play.api.libs.json.{ JsResultException, JsString, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_FROM_ADDRESS, TEST_HTML, TEST_PLAIN_TEXT, TEST_SERVICE, TEST_SUBJECT, TEST_TEMPLATE_ID }
import uk.gov.hmrc.email.services.Priority

class TemplateRenderResultSpec extends SpecBase {

  "priorityWrites" should {
    "write the object correctly" in new Setup {
      import TemplateRenderResult.priorityWrites

      Json.toJson(Priority.standard) mustBe JsString("standard")
    }
  }

  "templateRenderResultFormat" should {
    "read the json correctly" in new Setup {
      Json.parse(templateRenderResultJsonString).as[TemplateRenderResult] mustBe templateRenderResult
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(templateRenderResultInvalidJsonString).as[TemplateRenderResult]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(templateRenderResult) mustBe Json.parse(templateRenderResultJsonString)
    }
  }

  trait Setup {
    val templateRenderResult: TemplateRenderResult = TemplateRenderResult(
      plain = TEST_PLAIN_TEXT,
      html = TEST_HTML,
      fromAddress = TEST_FROM_ADDRESS,
      subject = TEST_SUBJECT,
      service = TEST_SERVICE,
      priority = Some(Priority.standard),
      templateId = Some(TEST_TEMPLATE_ID)
    )

    val templateRenderResultJsonString: String =
      """{
        |"plain":"test_title_text",
        |"html":"<head>test</head>",
        |"fromAddress":"test_address",
        |"subject":"test_sub",
        |"service":"test_service",
        |"priority":"standard",
        |"templateId":"test_template_id"
        |}""".stripMargin

    val templateRenderResultInvalidJsonString: String =
      """{
        |"html":"<head>test</head>",
        |"fromAddress":"test_address",
        |"subject":"test_sub",
        |"service":"test_service",
        |"priority":"standard",
        |"templateId":"test_template_id"
        |}""".stripMargin
  }
}

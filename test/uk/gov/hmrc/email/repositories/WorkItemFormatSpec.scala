/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.email.repositories

import org.mongodb.scala.bson.ObjectId
import play.api.libs.json.Json
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem
import java.time.Instant

class WorkItemFormatSpec extends SpecBase {

  "Reading from MongoDB as JSON" should {

    "read the legacy format from the existing PendingEmail mongo format into WorkItem" in {

      val json = Json.parse(
        """{"to":[],"templateId":"template","parameters":{},"tags":{},"force":false,"eventUrl":"http://test/callback/url","auditData":{}} """.stripMargin
      )

      json.as[QueuedEmailRequest] must be(
        QueuedEmailRequest(
          Nil,
          "template",
          Map.empty,
          Map.empty,
          false,
          Some("http://test/callback/url"),
          None,
          Map.empty,
          None
        )
      )
    }
    "read from the existing PendingEmail mongo format into WorkItem" in {

      val json = Json.parse("""{
                              |    "to" : [ ],
                              |    "templateId" : "template",
                              |    "parameters" : { },
                              |    "force" : false,
                              |    "renderedEmail" : {
                              |       "plain": "HELLO",
                              |       "html": "<H1>HELLO</H1>",
                              |       "fromAddress": "abc@test21.com",
                              |       "subject": "My Subject",
                              |       "templateRegime": "sa",
                              |       "templateId" : "templateId"
                              |    }
                              |  }""".stripMargin)

      json.as[QueuedEmailRequest] must be(
        QueuedEmailRequest(
          Nil,
          "template",
          Map.empty,
          Map.empty,
          false,
          None,
          None,
          Map.empty,
          Some(RenderResult("HELLO", "<H1>HELLO</H1>", "abc@test21.com", "My Subject", "sa", Some("templateId")))
        )
      )
    }

    "WorkItem should serialize to existing PendingEmail format (with availableAt and auditData)" in {
      val workItem = WorkItem(
        id = new ObjectId("5448d77f01000001000b84e7"),
        receivedAt = Instant.ofEpochMilli(1411139649671L),
        updatedAt = Instant.ofEpochMilli(1411139649671L),
        status = ToDo,
        failureCount = 0,
        availableAt = Instant.ofEpochMilli(1411139649671L),
        item = QueuedEmailRequest(
          Nil,
          "templateId",
          Map.empty,
          Map.empty,
          false,
          Some("http://test/callback/url"),
          None,
          Map.empty,
          Some(RenderResult("HELLO", "<H1>HELLO</H1>", "abc@test21.com", "My Subject", "sa", Some("templateId")))
        )
      )

      Json.toJson(workItem.item) must be(
        Json.parse(
          """
            |{"to":[],"templateId":"templateId","parameters":{},"tags":{},"force":false,"eventUrl":"http://test/callback/url","auditData":{},"renderedEmail":{"plain":"HELLO","html":"<H1>HELLO</H1>","fromAddress":"abc@test21.com","subject":"My Subject","templateRegime":"sa","templateId":"templateId"}}
            |""".stripMargin
        )
      )
    }
  }
}

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

import play.api.libs.json.Json
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.model.EventType.Sent
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import java.time.Instant
import java.util.UUID
import EmailEventsItem.emailEventsItemFormat

class EventsItemSpec extends SpecBase {

  "EmailEventsItem" must {
    "be serialized and deserialized" in {
      val eventStatusTime = Instant.parse("2023-02-02T00:00:00.000Z")
      val eventItem =
        EmailEventsItem(
          UUID.randomUUID().toString,
          Some("eventUrl"),
          Map(Sent -> eventStatusTime),
          Some("emailSource"),
          "tax.service.gov.uk"
        )
      val eventJson = Json.toJson(eventItem)

      eventJson.as[EmailEventsItem] mustBe eventItem
    }
  }
}

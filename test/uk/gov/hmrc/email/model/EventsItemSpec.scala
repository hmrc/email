/*
 * Copyright 2023 HM Revenue & Customs
 *
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

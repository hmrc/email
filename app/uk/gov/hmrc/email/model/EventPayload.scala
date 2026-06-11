/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, Reads }

case class EventPayload(df_payload: RawEvent)

object EventPayload {
  implicit val reads: Reads[EventPayload] = Json.reads[EventPayload]
}

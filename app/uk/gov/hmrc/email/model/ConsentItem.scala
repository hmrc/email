/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ JsError, JsString, JsSuccess, Json, Reads }

import java.time.Instant
import java.time.format.DateTimeFormatter

final case class ConsentItem(channel: String, address: String, consent: Boolean, reason: String, lastUpdated: Instant)

object ConsentItem {

  implicit def instantReads: Reads[Instant] =
    Reads[Instant] {
      case JsString(value) => JsSuccess(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(value)))
      case _               => JsError("ConsentItem lastUpdated date has incorrect format")
    }

  implicit val reads: Reads[ConsentItem] = Json.reads[ConsentItem]
}

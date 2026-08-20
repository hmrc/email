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

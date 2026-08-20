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

import play.api.libs.json._

case class MailgunId(id: String) {
  require(!id.startsWith("<"), "Mailgun IDs may not start with chevrons")
  require(!id.endsWith(">"), "Mailgun IDs may not end with chevrons")

  override def toString: String = id
}

object MailgunId {

  implicit val writes: Writes[MailgunId] = new Writes[MailgunId] {
    def writes(o: MailgunId) = JsString(o.id)
  }

  val withoutChevrons = (id: String) => id.replaceAll(">", "").replaceAll("<", "")

  implicit val reads: Reads[MailgunId] = __.read[String].map(id => MailgunId(withoutChevrons(id)))
}

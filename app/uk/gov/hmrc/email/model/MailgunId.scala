/*
 * Copyright 2023 HM Revenue & Customs
 *
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

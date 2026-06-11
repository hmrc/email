/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json._

case class MailgunSendResponse(id: MailgunId, message: String)

object MailgunSendResponse {
  implicit val formats: OFormat[MailgunSendResponse] = {
    implicit val mailgunIdWrites: Writes[MailgunId] = MailgunId.writes
    implicit val mailgunIdReads: Reads[MailgunId] = MailgunId.reads
    Json.format[MailgunSendResponse]
  }
}

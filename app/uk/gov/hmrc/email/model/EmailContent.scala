/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, OFormat, OWrites }
import uk.gov.hmrc.email.emailaddress.EmailAddress

final case class EmailContent(
  channel: String,
  from: String,
  to: List[To],
  callbackData: String,
  options: Options,
  contactPolicy: ContactPolicy,
  requestedReceipts: Seq[String],
  content: Content,
  notifyUrl: String
)

final case class To(email: List[EmailAddress], correlationId: String)

final case class Content(`type`: String, subject: String, replyTo: Option[EmailAddress], text: String, html: String)

final case class Options(trackClicks: Boolean, trackOpens: Boolean, fromName: String)

object EmailContent {
  implicit val toFormat: OWrites[To] = Json.writes[To]
  implicit val optionsFormat: OWrites[Options] = Json.writes[Options]
  implicit val contentFormat: OWrites[Content] = Json.writes[Content]
  implicit val EmailContentFormat: OWrites[EmailContent] = Json.writes[EmailContent]
}

final case class ContactPolicy(
  contactPolicyGroup: String,
  channelCheckConsent: Boolean,
  channelApplyFrequencyCap: Boolean
)
object ContactPolicy {
  implicit val format: OFormat[ContactPolicy] = Json.format[ContactPolicy]
}

object Channel {
  val EMAIL = "email"
}

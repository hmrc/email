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

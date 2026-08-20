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

package uk.gov.hmrc.email.mailgun

import play.api.Logging
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.email.model._
import uk.gov.hmrc.email.emailaddress.{ EmailAddress, PlayJsonFormats }
import java.time.Instant

object Converters extends Logging {

  def emailToFormBody(email: EmailMessage): Map[String, Seq[String]] = {

    val optionalParameters = Map[String, Option[String]](
      "h:reply-to" -> email.replyToAddress.map(_.value)
    ).filter(_._2.isDefined).view.mapValues(_.get).toMap

    val tags: Map[String, String] = email.tags.map { case (k, v) => (s"v:$k", v) }

    val mandatoryParameters: Map[String, String] = Map(
      "o:tracking-opens" -> "yes",
      "o:tag[0]"         -> s"regime.${email.templateRegime}",
      "o:tag[1]"         -> s"template.${email.templateId}",
      "o:tag[2]"         -> "mdtp",
      "from"             -> email.from,
      "to"               -> email.to.map(_.value).mkString(","),
      "subject"          -> email.subject,
      "text"             -> email.plainTextBody,
      "html"             -> email.htmlBody
    )
    (optionalParameters ++ mandatoryParameters ++ tags).view.mapValues(Seq(_)).toMap
  }

  private def intToDateTime(timestamp: BigDecimal) =
    Instant.ofEpochMilli((timestamp * 1000).toLong)

  def newMailgunEvent(
    id: String,
    emailAddress: String,
    detected: Instant,
    code: Option[Int],
    deletedDate: Option[Instant],
    mailgunId: Option[MailgunId],
    eventTypeName: String,
    severityName: Option[String],
    reason: Option[String],
    userVariables: Map[String, String],
    tags: Option[Set[Tag]],
    storageUrl: Option[String]
  ): Option[MailgunEvent] =
    if (EmailAddress.isValid(emailAddress))
      Some(
        MailgunEvent(
          id = id,
          emailAddress = EmailAddress(emailAddress),
          detected = detected,
          code = code,
          deletedDate = deletedDate,
          mailgunId = mailgunId,
          eventType = EventType(eventTypeName, severityName),
          regime = tags.flatMap(_.find { case RegimeTag(_) => true; case _ => false }),
          reason,
          userVariables,
          template = tags.flatMap(_.find { case TemplateTag(_) => true; case _ => false }),
          storageUrl
        )
      )
    else {
      logger.error(
        s"Failed to process $eventTypeName event with invalid emailAddress: $emailAddress and mailgunId: $mailgunId"
      )
      None
    }

  implicit val emailReads: Reads[EmailAddress] = PlayJsonFormats.emailAddressReads

  implicit val readMailgunEvent: Reads[Option[MailgunEvent]] =
    ((__ \ "id").read[String] and
      (__ \ "recipient")
        .read[String]
        .orElse(Reads { jsvalue =>
          logger.logger.warn(s"MailgunEvent readMailgunEvent recipient error, value: $jsvalue")
          JsSuccess(jsvalue.toString)
        }) and
      (__ \ "timestamp").read[BigDecimal].map(intToDateTime) and
      ((__ \ "delivery-status" \ "code").readNullable[Int] orElse Reads.pure(None)) and
      ((__ \ "message" \ "headers" \ "message-id")
        .readNullable[MailgunId] orElse Reads.pure(None)) and
      (__ \ "event").read[String] and
      (__ \ "severity").readNullable[String] and
      (__ \ "reason").readNullable[String] and
      (__ \ "user-variables")
        .readNullable[Map[String, String]]
        .orElse { jsValue =>
          logger.warn(s"MailgunEvent user-variables error, value: ${jsValue \ "user-variables"}")
          JsSuccess(None)
        }
        .map(_.getOrElse(Map.empty)) and
      (__ \ "tags").readNullable[Set[Tag]] and
      (__ \ "storage" \ "url").readNullable[String])(newMailgunEvent(_, _, _, _, None, _, _, _, _, _, _, _))

  implicit val readEventsAndNextPage: Reads[EventsPage] =
    ((__ \ "items").read(Reads.seq(readMailgunEvent)) and
      (__ \ "paging" \ "next").read[String])(EventsPage.apply)
}

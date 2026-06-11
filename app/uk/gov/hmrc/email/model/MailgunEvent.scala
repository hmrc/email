/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json._
import uk.gov.hmrc.email.emailaddress.EmailAddress
import java.time.Instant

sealed trait BounceEventType

enum EventType(val name: String, val emitExternally: Boolean = true) derives CanEqual {
  case Sent extends EventType("Sent")
  case Opened extends EventType("Opened")
  case Delivered extends EventType("Delivered")
  case Invalid extends EventType("Invalid")
  case Accepted extends EventType("Accepted", emitExternally = false)
  case Complained extends EventType("Complained", emitExternally = false)
  case PermanentBounce extends EventType("PermanentBounce") with BounceEventType
  case TemporaryBounce extends EventType("TemporaryBounce", emitExternally = false) with BounceEventType
  case Rejected extends EventType("Rejected")
}

implicit def eventToString(ev: EventType): String = ev.name

object EventType:
  given Writes[EventType] = Writes(event => JsString(event.name))

  def apply(eventTypeName: String, severityName: Option[String]): EventType = (eventTypeName, severityName) match
    case ("opened", _)                 => Opened
    case ("delivered", _)              => Delivered
    case ("accepted", _)               => Accepted
    case ("complained", _)             => Complained
    case ("failed", Some("permanent")) => PermanentBounce
    case ("failed", _)                 => TemporaryBounce
    case ("rejected", _)               => Rejected
    case _                             => Invalid

sealed trait Tag {
  val name: String
}

object Tag {

  val tagReads = new Reads[Tag] {
    def reads(json: JsValue) =
      json.validate[String].map(tag => Tag.apply(tag))
  }
  val tagWrites = new Writes[Tag] {
    def writes(tag: Tag) = JsString(tag.name)
  }
  implicit val tagFormat: Format[Tag] =
    Format(tagReads, tagWrites)

  private lazy val mailgunTagPattern = "(regime|template)[\\.|_](.+)".r

  def apply(tag: String): Tag =
    tag match {
      case mailgunTagPattern("regime", regime)     => RegimeTag(regime)
      case mailgunTagPattern("template", template) => TemplateTag(template)
      case _ =>
        new Tag {
          override val name: String = tag
        }
    }
}

case class RegimeTag(name: String) extends Tag
case class TemplateTag(name: String) extends Tag

case class Bounce(
  emailAddress: String,
  detected: Instant,
  code: Option[Int],
  deletedDate: Option[Instant] = None,
  emailSource: Option[String] = None,
  mailgunEventId: Option[String] = None
)

case class Enrolment(enrolment: Option[String])
case object Enrolment {
  implicit val enrolmentReads: Reads[Enrolment] = Json.reads[Enrolment]
}

case class MailgunEvent(
  id: String,
  emailAddress: EmailAddress,
  detected: Instant,
  code: Option[Int],
  deletedDate: Option[Instant],
  mailgunId: Option[MailgunId],
  eventType: EventType,
  regime: Option[Tag],
  reason: Option[String],
  tags: Map[String, String],
  template: Option[Tag],
  storageUrl: Option[String]
) {
  def enrolment: Option[String] = tags.get("enrolment")
}

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

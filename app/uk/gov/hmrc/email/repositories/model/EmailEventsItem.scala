/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories.model

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{ Format, JsObject, JsValue, Json, Reads, Writes, __ }
import uk.gov.hmrc.email.model.EventType
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import java.time.Instant

case class EmailEventsItem(
  messageId: String,
  eventUrl: Option[String],
  events: Map[EventType, Instant],
  emailSource: Option[String] = None,
  senderDomain: String
)

object EmailEventsItem {
  implicit val dateFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
  implicit val eventsMapReads: Reads[Map[EventType, Instant]] =
    __.read[JsObject].map { obj =>
      EventType.values.flatMap { eventType =>
        (obj \ eventType.name).asOpt[Instant].map(eventType -> _)
      }.toMap
    }
  implicit val eventsMapWrites: Writes[Map[EventType, Instant]] =
    new Writes[Map[EventType, Instant]] {
      def writes(o: Map[EventType, Instant]): JsValue =
        if (o.isEmpty)
          Json.obj()
        else {
          val jsonObjects = o.map { case (eventType, time) => Json.obj(eventType.name -> time) }
          jsonObjects.foldLeft(Json.obj())(_ ++ _)
        }
    }
  implicit val emailEventsItemFormat: Format[EmailEventsItem] = {
    val reads =
      ((__ \ "messageId").read[String] and
        (__ \ "eventUrl").readNullable[String] and
        __.read[Map[EventType, Instant]] and
        (__ \ "emailSource").readNullable[String] and
        (__ \ "senderDomain").readNullable[String].map(_.getOrElse("")))(EmailEventsItem.apply)

    val writes =
      ((__ \ "messageId").write[String] and
        (__ \ "eventUrl").writeNullable[String] and
        __.write[Map[EventType, Instant]] and
        (__ \ "emailSource")
          .writeNullable[String] and (
          (__ \ "senderDomain").write[String]
        ))(o => Tuple.fromProductTyped[EmailEventsItem](o))

    Format(reads, writes)
  }
}

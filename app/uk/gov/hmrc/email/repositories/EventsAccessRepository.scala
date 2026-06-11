/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories

import org.mongodb.scala.model.{ Filters, UpdateOptions, Updates }
import play.api.Logger
import play.api.libs.json.{ Format, Json }
import uk.gov.hmrc.email.model.MailgunEvent
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import java.time.Instant
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

case class EventAccess(lastAccessed: Instant, _id: String)

object EventAccessFormats {
  val default = "bouncesLastAccessed"
  def bouncesLastAccessed(senderDomain: String): String =
    s"${senderDomain}_$default"

  implicit val dateFormat: Format[Instant] =
    MongoJavatimeFormats.instantFormat
  val format: Format[EventAccess] = Format(Json.reads[EventAccess], Json.writes[EventAccess])
}

@Singleton
class EventsAccessRepository @Inject() (mongo: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository[EventAccess](mongo, "eventsAccessLog", EventAccessFormats.format, Seq.empty) {

  import EventAccessFormats.*

  private val logger = Logger(getClass)

  def getLastAccessed(senderDomain: String): Future[Option[Instant]] =
    collection
      .find(Filters.equal("_id", bouncesLastAccessed(senderDomain)))
      .first()
      .toFuture()
      .map(Option(_).map(_.lastAccessed))

  def saveLatestDetected(senderDomain: String, events: Seq[MailgunEvent]): Future[Unit] =
    events
      .map(_.detected)
      .sortWith { case (first, second) =>
        first.isAfter(second)
      }
      .headOption
      .map { latestDetected =>
        logger.info(s"Saving last event accessed timestamp $latestDetected")
        val lastAccessed = bouncesLastAccessed(senderDomain)
        collection
          .updateOne(
            Filters.equal("_id", lastAccessed),
            Updates.set("lastAccessed", latestDetected),
            UpdateOptions().upsert(true)
          )
          .toFuture()
          .map { _ =>
            ()
          }
      }
      .getOrElse(Future.successful(()))
}

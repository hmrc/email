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

package uk.gov.hmrc.email.repositories

import org.apache.commons.codec.binary.Base64
import org.bson.types.ObjectId
import org.mongodb.scala.DuplicateKeyException
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{ Filters, IndexModel, IndexOptions }
import play.api.libs.json.*
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.mongo.play.json.formats.{ MongoFormats, MongoJavatimeFormats }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem, WorkItemFields, WorkItemRepository }
import uk.gov.hmrc.mongo.{ MongoComponent, MongoUtils }
import java.security.MessageDigest
import java.time.{ Duration, Instant }
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EventHubRepository @Inject() (collectionName: String, configuration: Configuration, mongo: MongoComponent)(
  implicit ec: ExecutionContext
) extends WorkItemRepository[EventHubItem](
      collectionName,
      mongo,
      EventHubItem.workItemFormat,
      WorkItemFields(
        id = "_id",
        receivedAt = "createdAt",
        updatedAt = "updatedAt",
        availableAt = "availableAt",
        failureCount = "failureCount",
        status = "status",
        item = "item"
      )
    ) {

  private val logger = Logger(getClass)
  private final val DuplicateKeyDbErrorCode = 11000

  lazy val expireAfterSecondsTTL: Long = {
    val DEFAULT_TTL_SECONDS: Long = 259200 // defaulting to 3 days
    configuration
      .getOptional[Long]("event-hub.expireAfterSeconds")
      .getOrElse(DEFAULT_TTL_SECONDS)
  }

  lazy val failedBefore: FiniteDuration =
    configuration
      .getOptional[FiniteDuration]("event-hub.failedBefore")
      .getOrElse(FiniteDuration(15, "minutes"))

  lazy val augmentedIndexes: Seq[IndexModel] = Seq(
    IndexModel(
      ascending("item.hash"),
      IndexOptions().name("hashIndex").background(true).unique(true).sparse(true)
    ),
    IndexModel(
      ascending(workItemFields.receivedAt),
      IndexOptions()
        .name("createdAtIndex")
        .background(true)
        .expireAfter(expireAfterSecondsTTL, TimeUnit.SECONDS)
    )
  ) ++ indexes

  override def ensureIndexes(): Future[Seq[String]] =
    MongoUtils.ensureIndexes(collection, augmentedIndexes, false).recover { case e =>
      logger.error(s"Ensure Indexes failed for collection $collectionName ${e.getMessage}")
      augmentedIndexes.map(_.getOptions.getName)
    }

  def pushEventHubItem(eventHubItem: EventHubItem): Future[SaveEventHubResult] =
    pushNew(eventHubItem)
      .map(_ => ItemSaved)
      .recover {
        case exception: Exception if exception.getMessage.contains("E11000 duplicate key error collection") =>
          logger.warn(
            s"Duplicate key error in eventhub collection: ${exception.getMessage} with transId ${eventHubItem.messageId} "
          )
          DuplicateEventHubItem
        case exception: Exception =>
          logger.error(s"Unknown Error when trying to save event in eventhub collection: ${exception.getMessage}")
          ItemSaveFailed
      }

  def pushNewMailgunEventHubItems(eventHubItems: Seq[EventHubItem]): Future[Seq[WorkItem[EventHubItem]]] =
    pushNewBatch(eventHubItems).recover {
      case exception: DuplicateKeyException =>
        logger.warn(
          s"pushNewMailgunEventHubItems - event already in event_hub collection - ${eventHubItems.size} " +
            s"event hub items: ${exception.getMessage}"
        )
        Seq.empty
      case exception: Exception =>
        if (!exception.getMessage.contains(s"E$DuplicateKeyDbErrorCode"))
          logger.error(
            s"pushNewMailgunEventHubItems - an error occurred whilst attempting to store ${eventHubItems.size} " +
              s"event hub items: ${exception.getMessage} in events_hub collection"
          )
        Seq.empty
    }

  def pullOutstandingEventHubItem: Future[Option[WorkItem[EventHubItem]]] =
    pullOutstanding(now().minusMillis(failedBefore.toMillis), now())

  def eventHubItem(transId: String): Future[Option[WorkItem[EventHubItem]]] =
    collection
      .find(Filters.equal("item.id", transId))
      .toFuture()
      .map(_.headOption)

  def failEventHubItem(e: WorkItem[EventHubItem]): Future[Boolean] =
    markAs(e.id, ProcessingStatus.Failed)

  def permanentlyFailEventHubItem(e: WorkItem[EventHubItem]): Future[Boolean] =
    markAs(e.id, ProcessingStatus.PermanentlyFailed)

  def findEventHubItem(e: WorkItem[EventHubItem]): Future[Option[WorkItem[EventHubItem]]] =
    findById(e.id)

  override def now(): Instant = Instant.now()

  override def inProgressRetryAfter: Duration =
    configuration.underlying.getDuration("event-hub.event-emitter.inProgressRetryAfter.millis")
}

case class EventHubItem(
  id: String,
  eventId: UUID,
  emailAddress: String,
  detected: Instant,
  eventType: String,
  reason: String,
  tags: Map[String, String],
  code: Option[Int],
  messageId: String,
  hash: String,
  template: Option[Tag]
)
object EventHubItem {

  def apply(
    id: String,
    eventId: UUID,
    emailAddress: String,
    detected: Instant,
    eventType: String,
    reason: String,
    tags: Map[String, String],
    code: Option[Int],
    messageId: String,
    template: Option[Tag]
  ): EventHubItem = {
    val hash: String = {
      val sha256Digester = MessageDigest.getInstance("SHA-256")
      new String(
        Base64.encodeBase64(
          sha256Digester.digest(
            Seq(
              id,
              emailAddress,
              detected.toString,
              eventType,
              reason,
              tags.toString(),
              code.getOrElse(0).toString
            ).mkString("/").getBytes("UTF-8")
          )
        )
      )
    }
    EventHubItem(id, eventId, emailAddress, detected, eventType, reason, tags, code, messageId, hash, template)
  }

  implicit val dateFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
  implicit val fmt: OFormat[EventHubItem] = Json.format[EventHubItem]
  implicit val objectIdFmt: Format[ObjectId] = MongoFormats.objectIdFormat
  implicit val workItemFormat: Format[EventHubItem] = Json.format[EventHubItem]
}

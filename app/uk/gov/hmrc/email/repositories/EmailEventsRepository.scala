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

package uk.gov.hmrc.email.repositories

import cats.instances.list.*
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{ Filters, IndexModel, IndexOptions, Updates }
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.model.EventType.Sent
import uk.gov.hmrc.email.model.{ EventMarkingStatus, EventType }
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import uk.gov.hmrc.mongo.workitem.*
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.{ MongoComponent, MongoUtils }
import java.time
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EmailEventsRepository @Inject() (
  configuration: Configuration,
  mongo: MongoComponent
)(implicit ec: ExecutionContext)
    extends WorkItemRepository[EmailEventsItem](
      "email_events",
      mongo,
      EmailEventsItem.emailEventsItemFormat,
      WorkItemFields(
        id = "_id",
        receivedAt = "createdAt",
        updatedAt = "updatedAt",
        availableAt = "availableAt",
        failureCount = "failureCount",
        status = "status",
        item = ""
      )
    ) with EventWorkCommands {

  private val logger: Logger = Logger(getClass)

  lazy val sentEventInDays: Long = configuration.get[Long]("events-expiry.sentEventInDays")
  lazy val deliveredEventInDays: Long =
    configuration.get[Long]("events-expiry.deliveredEventInDays")
  lazy val openedEventInDays: Long =
    configuration.get[Long]("events-expiry.openedEventInDays")
  lazy val permanentBounceEventInDays: Long =
    configuration.get[Long]("events-expiry.permanentBounceEventInDays")

  lazy val augmentedIndexes: Seq[IndexModel] = Seq(
    IndexModel(
      ascending("messageId"),
      IndexOptions().name("messageId").unique(true)
    ),
    IndexModel(
      ascending("Sent"),
      IndexOptions().name("sentEvent").background(true).expireAfter(sentEventInDays, TimeUnit.DAYS)
    ),
    IndexModel(
      ascending("Delivered"),
      IndexOptions()
        .name("deliveredEvent")
        .sparse(true)
        .background(true)
        .expireAfter(deliveredEventInDays, TimeUnit.DAYS)
    ),
    IndexModel(
      ascending("Opened"),
      IndexOptions().name("openedEvent").sparse(true).background(true).expireAfter(openedEventInDays, TimeUnit.DAYS)
    ),
    IndexModel(
      ascending("PermanentBounce"),
      IndexOptions()
        .name("permanentBounceEvent")
        .sparse(true)
        .background(true)
        .expireAfter(permanentBounceEventInDays, TimeUnit.DAYS)
    ),
    IndexModel(
      ascending("senderDomain", "Sent"),
      IndexOptions().name("senderDomainSent").background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Opened"),
      IndexOptions().name("senderDomainOpened").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Delivered"),
      IndexOptions().name("senderDomainDelivered").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Invalid"),
      IndexOptions().name("senderDomainInvalid").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Accepted"),
      IndexOptions().name("senderDomainAccepted").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Complained"),
      IndexOptions().name("senderDomainComplained").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "PermanentBounce"),
      IndexOptions().name("senderDomainPermanentBounce").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "TemporaryBounce"),
      IndexOptions().name("senderDomainTemporaryBounce").sparse(true).background(true)
    ),
    IndexModel(
      ascending("senderDomain", "Rejected"),
      IndexOptions().name("senderDomainRejected").sparse(true).background(true)
    )
  ) ++ indexes

  override def ensureIndexes(): Future[Seq[String]] =
    MongoUtils.ensureIndexes(collection, augmentedIndexes, replaceIndexes = true).recover { case e =>
      logger.error(s"Ensure Indexes failed for collection $collectionName ${e.getMessage}")
      augmentedIndexes.map(_.getOptions.getName)
    }

  override def now(): Instant = Instant.now()
  override val inProgressRetryAfter: time.Duration =
    configuration.underlying.getDuration("message.event-emitter.inProgressRetryAfter.millis")

  def markSent(
    messageId: String,
    eventUrl: Option[String],
    emailSource: Option[String] = None,
    senderDomain: String,
    availableAt: Instant = now(),
    eventsSentAt: Instant = now()
  ): Future[WorkItem[EmailEventsItem]] = {
    logger.warn(s"markSentEmailEvents $messageId")
    pushNew(
      EmailEventsItem(
        messageId = messageId,
        eventUrl = eventUrl,
        events = Map(Sent -> eventsSentAt),
        emailSource,
        senderDomain
      ),
      availableAt
    )
  }

  def markEvent(messageId: String, eventType: EventType, detected: Instant): Future[EventMarkingStatus] = {
    import EventMarkingStatus.*
    logger.warn(s"markEventEmailEvents $messageId with $eventType with $detected")
    val eventInFinalisedState: Seq[Bson] =
      if (eventType.emitExternally)
        Seq(Updates.set("status", ToDo), Updates.set(workItemFields.failureCount, 0))
      else Seq.empty[Bson]

    val setDetectedTimeAndStatus: Seq[Bson] =
      eventInFinalisedState.+:(
        Updates
          .set(eventType.name, detected)
      )

    val idNotAlreadyMarked: Bson =
      Filters.and(Filters.equal("messageId", messageId), Filters.exists(eventType.name, false))

    collection
      .findOneAndUpdate(idNotAlreadyMarked, setDetectedTimeAndStatus)
      .toFuture()
      .map(Option(_).fold[EventMarkingStatus](AlreadyMarkedOrNotFound)(_ => Marked))
  }

  def findEvent(messageId: String): Future[Option[WorkItem[EmailEventsItem]]] =
    collection.find(Filters.equal("messageId", messageId)).toFuture().map(_.headOption)

  lazy val retryInterval: Long =
    configuration
      .getOptional[Duration]("message.event-emitter.retryInterval")
      .map(_.toMillis)
      .getOrElse(throw new RuntimeException("message.queue.retryInterval not specified"))
  lazy val numberOfRetries: Int =
    configuration
      .getOptional[Int]("message.event-emitter.numberOfRetries")
      .getOrElse(throw new RuntimeException("message.queue.numberOfRetries not specified"))

  override def pullOutstanding(): Future[Option[WorkItem[EmailEventsItem]]] =
    super.pullOutstanding(failedBefore = now().minus(retryInterval, ChronoUnit.MILLIS), availableBefore = now())

  override def markComplete(
    item: WorkItem[EmailEventsItem],
    newStatus: ProcessingStatus & ResultStatus
  ): Future[Boolean] =
    super.complete(
      item.id,
      newStatus match {
        case ProcessingStatus.Failed if item.failureCount + 1 >= numberOfRetries =>
          ProcessingStatus.PermanentlyFailed
        case other => other
      }
    )
}

trait EventWorkCommands {
  def pullOutstanding(): Future[Option[WorkItem[EmailEventsItem]]]
  def markComplete(item: WorkItem[EmailEventsItem], newStatus: ProcessingStatus & ResultStatus): Future[Boolean]
}

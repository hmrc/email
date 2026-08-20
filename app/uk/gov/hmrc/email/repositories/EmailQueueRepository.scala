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

import org.mongodb.scala.bson.{ BsonDocument, ObjectId }
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.*
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.mongo.metrix.MetricSource
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem, WorkItemFields, WorkItemRepository }
import uk.gov.hmrc.mongo.{ MongoComponent, MongoUtils }
import java.time
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Singleton
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
case class EmailQueueRepository(collectName: String, configuration: Configuration, mongo: MongoComponent)(implicit
  ec: ExecutionContext
) extends WorkItemRepository[QueuedEmailRequest](
      collectName,
      mongo,
      QueuedEmailRequest.format,
      WorkItemFields(
        id = "_id",
        receivedAt = "modifiedDetails.createdAt",
        updatedAt = "modifiedDetails.lastUpdated",
        availableAt = "availableAt",
        failureCount = "failures",
        status = "status",
        item = "templatedEmailRequest"
      )
    ) {

  lazy val retryInterval: Long =
    configuration
      .getOptional[Duration]("message.queue.retryInterval")
      .map(_.toMillis)
      .getOrElse(throw new RuntimeException("message.queue.retryInterval"))

  override def toString: String =
    s"${this.getClass.getSimpleName}: collection: $collectName"

  override def inProgressRetryAfter: time.Duration =
    configuration.underlying.getDuration("message.queue.inProgressRetryAfter.millis")

  override def now(): Instant = Instant.now()

  lazy val augmentedIndexes: Seq[IndexModel] = Seq(
    IndexModel(
      ascending("templatedEmailRequest.templateId"),
      IndexOptions().background(true)
    )
  ) ++ indexes

  private val logger: Logger = Logger(getClass)
  override def ensureIndexes(): Future[Seq[String]] =
    MongoUtils.ensureIndexes(collection, augmentedIndexes, replaceIndexes = true).recover { case e =>
      logger.error(s"Ensure Indexes failed for collection $collectionName ${e.getMessage}")
      augmentedIndexes.map(_.getOptions.getName)
    }

  def enqueue(sendEmailRequest: QueuedEmailRequest): Future[WorkItem[QueuedEmailRequest]] = {
    logger.warn(s"enqueue ${sendEmailRequest.templateId}")
    super.pushNew(sendEmailRequest)
  }

  def pullPendingEmail(availableBefore: Instant = now()): Future[Option[WorkItem[QueuedEmailRequest]]] =
    super.pullOutstanding(availableBefore.minus(retryInterval, ChronoUnit.MILLIS), availableBefore)

  def complete(id: ObjectId)(implicit ec: ExecutionContext): Future[Boolean] = {
    val selector = Filters.and(Filters.equal("_id", id), Filters.equal("status", ProcessingStatus.InProgress.name))
    collection.deleteMany(selector).toFuture().map(_.getDeletedCount > 0)
  }

  def metricsByTemplateId()(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    mongo.database
      .getCollection[BsonDocument](collectName)
      .aggregate(Seq(Aggregates.group("$templatedEmailRequest.templateId", Accumulators.sum("count", 1))))
      .toFuture()
      .map(_.flatMap(doc => Map(doc.get("_id").asString().getValue -> doc.get("count").asInt32().getValue)).toMap)

  def createMetric(): MetricSource =
    (_: ExecutionContext) => metrics

}

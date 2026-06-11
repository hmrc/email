/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories

import com.google.inject.Singleton
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.*
import org.mongodb.scala.ReadPreference
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.email.model.MetricPrefix
import uk.gov.hmrc.email.repositories.model.EmailStats
import uk.gov.hmrc.email.utils.EmailStatus
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.metrix.MetricSource
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EmailStatsRepository @Inject() (mongo: MongoComponent, configuration: Configuration)(implicit
  executionContext: ExecutionContext
) extends PlayMongoRepository[EmailStats](
      mongo,
      "email_stats",
      EmailStats.format,
      Seq(
        IndexModel(
          ascending("name"),
          IndexOptions()
            .name("email_stats_name_index")
            .unique(true)
        ),
        IndexModel(
          ascending("createdAt"),
          IndexOptions()
            .name("email_stats_createdAt_index")
            .expireAfter(configuration.get[Long]("email-stats.resetIntervalDays"), TimeUnit.DAYS)
        )
      ),
      replaceIndexes = true
    ) with Logging with MetricSource {

  def put(metricPrefix: MetricPrefix, metric: String, emailStatus: EmailStatus): Future[Unit] =
    collection
      .updateOne(
        Filters.eq("name", s"${metricPrefix.name}.$metric.${emailStatus.name}"),
        Updates.combine(
          Updates.inc("count", 1),
          Updates.setOnInsert("createdAt", Instant.now())
        ),
        new UpdateOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def getMetrics: MetricSource = { (_: ExecutionContext) =>
    metrics
  }

  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    collection
      .withReadPreference(ReadPreference.secondaryPreferred())
      .find(Filters.empty())
      .toFuture()
      .map { statsSeq =>
        statsSeq.map(stat => stat.name -> stat.count.toInt).toMap
      }

}

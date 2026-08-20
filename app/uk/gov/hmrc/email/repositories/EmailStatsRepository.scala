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

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

package uk.gov.hmrc.email.metrics

import com.typesafe.config.Config
import play.api.Logging
import uk.gov.hmrc.email.repositories.EmailStatsRepository
import uk.gov.hmrc.mongo.metrix.{ MetricSource, PersistedMetric }
import javax.inject.{ Inject, Singleton }
import scala.language.postfixOps
import scala.util.Try
import scala.util.matching.Regex

trait MetricOrchestratorConfiguration {
  val sources: List[MetricSource]
  val resetRegex: Regex
  val resetOn: PersistedMetric => Boolean = metric =>
    metric.count != 0 &&
      resetRegex.findFirstIn(metric.name).isDefined
}

@Singleton
class ScheduledMetrics @Inject() (
  mailboxMetrics: MailboxMetrics,
  config: Config,
  emailStatsRepository: EmailStatsRepository
) extends MetricOrchestratorConfiguration with Logging {

  private val countByStatusSource: Boolean = getBooleanConfig("sources.mailboxMetrics.countByStatus")
  private val countByTemplateSource: Boolean = getBooleanConfig("sources.mailboxMetrics.countByTemplate")

  private def addMetricSource[T](condition: Boolean, value: => List[T]): List[T] =
    if (condition) value else List.empty[T]

  val sources: List[MetricSource] =
    addMetricSource(true, List(emailStatsRepository.getMetrics)) ++
      addMetricSource(countByStatusSource, mailboxMetrics.countByStatus) ++
      addMetricSource(countByTemplateSource, mailboxMetrics.countByTemplate)

  val resetRegex: Regex = s"""queues.(?:[^\\.]+).templates""".r

  private def getBooleanConfig(path: String): Boolean =
    Try(config.getBoolean(path)).getOrElse(false)
}

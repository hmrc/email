/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.metrics

import play.api.Logging
import javax.inject.{ Inject, Singleton }
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.services.Routers
import uk.gov.hmrc.mongo.metrix.MetricSource
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class MailboxMetrics @Inject() (routers: Routers) extends Logging {

  def createMetric(repo: EmailQueueRepository, senderDomainName: String): MetricSource =
    new MetricSource {
      def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
        val toMetricName = (templateId: String) =>
          s"${senderDomainName.replace(".", "_")}.queues.${repo.metricPrefix}.templates.$templateId"
        logger.debug(s"Metric name: $toMetricName")

        for {
          allRepoMetricsById <- repo.metricsByTemplateId()
          _ = logger.debug(s"createMetric: allRepoMetricsById count ${allRepoMetricsById.size}")
          templateIdMetrics = allRepoMetricsById.map { case (key, value) =>
                                toMetricName(key) -> value
                              }
        } yield templateIdMetrics
      }
    }

  lazy val countByStatus: List[MetricSource] =
    routers.all.values
      .flatMap(router =>
        router.outboxes.map {
          val outboxes =
            router.outboxes.map(a => s"\nSender domain: ${a.senderDomain} queue: ${a.queueName}").mkString(", ")
          logger.debug(s"countByStatus: ${router.outboxes.size}, outboxes: $outboxes")
          _.emailRepository.createMetric()
        }
      )
      .toList

  lazy val countByTemplate: List[MetricSource] = routers.all.values.flatMap { router =>
    List(
      createMetric(router.backgroundOutbox.emailRepository, router.senderDomainName),
      createMetric(router.defaultOutbox.emailRepository, router.senderDomainName)
    )
  }.toList

  lazy val sources: Seq[MetricSource] = countByStatus ++ countByTemplate

  override def toString: String =
    s"${this.getClass.getSimpleName}: sources: ${this.sources.map(_.toString).mkString(", ")}"
}

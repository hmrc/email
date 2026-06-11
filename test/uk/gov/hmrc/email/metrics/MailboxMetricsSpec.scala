/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.metrics

import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.{ Application, inject }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.mongo.metrix.MetricSource

import scala.concurrent.{ ExecutionContext, Future }

class MailboxMetricsSpec extends SpecBase {

  "countByStatus" should {
    "return correct count of created MetricSource" in new Setup {
      when(mockEmailQueueRepository.createMetric()).thenReturn(metricSource)

      mailboxMetrics.countByStatus.size must be(21)
    }
  }

  "countByTemplate" should {
    "return correct count  of templates" in new Setup {
      when(mockEmailQueueRepository.createMetric()).thenReturn(metricSource)

      mailboxMetrics.countByTemplate.size must be(14)
    }
  }

  "sources" should {
    "return correct count of created MetricSource" in new Setup {
      when(mockEmailQueueRepository.createMetric()).thenReturn(metricSource)

      mailboxMetrics.sources.size must be(35)
    }
  }

  "toString" should {
    "return the correct String representation of the class" in new Setup {
      mailboxMetrics.toString must not be empty
    }
  }

  trait Setup {
    val metricSource: MetricSource = new MetricSource {
      override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = Future(Map("test" -> 1))
    }

    val mockEmailQueueRepository: EmailQueueRepository = mock[EmailQueueRepository]
    val application: Application = applicationBuilder
      .overrides(
        inject.bind[EmailQueueRepository].toInstance(mockEmailQueueRepository)
      )
      .configure(
        "microservice.metrics.enabled" -> false,
        "metrics.enabled"              -> false,
        "auditing.enabled"             -> false
      )
      .build()

    val mailboxMetrics: MailboxMetrics = application.injector.instanceOf[MailboxMetrics]
  }
}

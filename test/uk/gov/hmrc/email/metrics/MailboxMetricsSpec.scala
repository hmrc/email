/*
 * Copyright 2025 HM Revenue & Customs
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
        "metrics.enabled"              -> false
      )
      .build()

    val mailboxMetrics: MailboxMetrics = application.injector.instanceOf[MailboxMetrics]
  }
}

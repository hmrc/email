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

package uk.gov.hmrc.email.metrics

import com.typesafe.config.Config
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ times, verify, verifyNoMoreInteractions, when }
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.connectors.MailgunConnectors
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EmailStatsRepository }
import uk.gov.hmrc.mongo.metrix.{ MetricSource, PersistedMetric }
import scala.util.matching.Regex

class MetricOrchestratorConfigurationSpec extends SpecBase {

  "MetricOrchestratorConfiguration" should {

    "reset values different from 0 only" in {
      val mockRegex: Regex = mock[Regex]
      when(mockRegex.findFirstIn(any[CharSequence])).thenReturn(Some("some-result"))

      val resetAllNamesConfiguration = new MetricOrchestratorConfiguration {
        val sources: List[MetricSource] = List.empty

        val resetRegex: Regex = mockRegex
      }

      resetAllNamesConfiguration.resetOn(PersistedMetric("name", 0)) mustBe false
      resetAllNamesConfiguration.resetOn(PersistedMetric("name", 1)) mustBe true
      resetAllNamesConfiguration.resetOn(PersistedMetric("name", -1)) mustBe true
    }

    "check regex when different from 0 only" in {
      val mockRegex: Regex = mock[Regex]
      when(mockRegex.findFirstIn(any[CharSequence])).thenReturn(None)

      val resetNothingConfiguration = new MetricOrchestratorConfiguration {
        val sources: List[MetricSource] = List.empty
        val resetRegex: Regex = mockRegex
      }

      resetNothingConfiguration.resetOn(PersistedMetric("name", 1)) mustBe false
      verify(mockRegex, times(1)).findFirstIn("name")

      resetNothingConfiguration.resetOn(PersistedMetric("name", 0)) mustBe false
      resetNothingConfiguration.resetOn(PersistedMetric("another-name", 0)) mustBe false
      verifyNoMoreInteractions(mockRegex)
    }

  }

  "ScheduledMetrics reset on" should {
    "reset matching *.queues.*.templates.*" in new TestCase {
      allMetrics.filter(scheduledMetrics.resetOn) must
        contain only queueTemplate
    }
  }

  trait TestCase {
    val mockConfig = mock[Config]

    private def createNonEmptyMetric(name: String) = PersistedMetric(name, 2)

    val regimeOpen: PersistedMetric = createNonEmptyMetric("mailgun.qa_tax_service_gov_uk.regime.opened.off_payroll")
    val oldRegimeOpen: PersistedMetric = createNonEmptyMetric(
      "mailgun.template.opened.cato_access_invitation_template_id"
    )
    val regimeDelivered: PersistedMetric = createNonEmptyMetric("mailgun.qa_tax_service_gov_uk.regime.delivered.ats")
    val templateSent: PersistedMetric = createNonEmptyMetric(
      "mailgun.qa_tax_service_gov_uk.template.sent.apiapplicationapprovednotification"
    )
    val oldTemplateSent: PersistedMetric = createNonEmptyMetric(
      "mailgun.template.sent.apideveloperchangedpasswordconfirmation"
    )
    val templateDelivered: PersistedMetric = createNonEmptyMetric(
      "mailgun.qa_tax_service_gov_uk.template.delivered.dfs_submission_success_r39_2015"
    )
    val justSent: PersistedMetric = createNonEmptyMetric("mailgun.template.sent.dfs_submission_success_cis_2015")
    val justDelivered: PersistedMetric = createNonEmptyMetric(
      "mailgun.template.delivered.apiapplicationapprovednotification"
    )
    val oldJustDelivered: PersistedMetric = createNonEmptyMetric("mailgun.delivered")
    val queueTemplate: PersistedMetric = createNonEmptyMetric(
      "qa_tax_service_gov_uk.queues.hmrc_backgroundQueue.templates.someTemplateId"
    )
    val queueTodo: PersistedMetric = createNonEmptyMetric("hmrc_urgentQueue.todo")
    val backgroundQueueFailed: PersistedMetric = createNonEmptyMetric("hmrc_backgroundQueue.failed")
    val mailgunEventDuplicate: PersistedMetric = createNonEmptyMetric("mailgunEvents.duplicate")
    val mockMailgunConnectors: MailgunConnectors = mock[MailgunConnectors]
    val mockMailboxMetrics: MailboxMetrics = mock[MailboxMetrics]
    when(mockMailboxMetrics.countByStatus).thenReturn(List.empty)
    when(mockMailboxMetrics.countByTemplate).thenReturn(List.empty)
    val mockEmailEvents: EmailEventsRepository = mock[EmailEventsRepository]
    val mockEmailStatsRepository: EmailStatsRepository = mock[EmailStatsRepository]
    val scheduledMetrics: ScheduledMetrics =
      new ScheduledMetrics(mockMailboxMetrics, mockConfig, mockEmailStatsRepository)

    val allMetrics: Seq[PersistedMetric] = Seq(
      regimeOpen,
      oldRegimeOpen,
      regimeDelivered,
      templateSent,
      oldTemplateSent,
      templateDelivered,
      justSent,
      justDelivered,
      oldJustDelivered,
      queueTodo,
      backgroundQueueFailed,
      mailgunEventDuplicate,
      queueTemplate
    )

  }

}

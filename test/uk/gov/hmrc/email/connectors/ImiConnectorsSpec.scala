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

package uk.gov.hmrc.email.connectors

import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.services.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global

class ImiConnectorsSpec extends SpecBase with ScalaFutures {

  "ImiConnectors" must {
    "have all connectors defined in configuration" in new TestSetUp {

      val config: Map[String, SenderDomainConfiguration] =
        Map(buildConfig("domain1", true), buildConfig("domain2", false))

      when(senderDomainConfigurationLoader.default).thenReturn(config)

      val imiConnectors =
        new ImiConnectors(
          servicesConfig,
          senderDomainConfigurationLoader,
          httpClient,
          fakeAuditConnector
        )

      val connectors: Map[String, ImiConnector] = imiConnectors.all

      connectors.size mustBe 2
      connectors.keys mustBe Set("domain1", "domain2")

    }
  }

  class TestSetUp {
    val servicesConfig = mock[ServicesConfig]
    val senderDomainConfigurationLoader = mock[SenderDomainConfigurationLoader]
    val httpClient = mock[HttpClientV2]
    val auditing = mock[HttpAuditing]
    val conf = mock[Configuration]
    val actorSystem = mock[ActorSystem]

    val fakeAuditConnector: AuditConnector = new AuditConnector {
      var auditEvents: List[DataEvent] = List.empty

      override def auditingConfig: AuditingConfig = ???

      override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
        Future
          .successful(AuditResult.Success)
          .andThen { case _ =>
            auditEvents = event.asInstanceOf[DataEvent] :: auditEvents
          }(ec)

      override def auditChannel: AuditChannel = ???

      override def datastreamMetrics: DatastreamMetrics = ???
    }
    def buildConfig(domainName: String, imiConnector: Boolean) =
      domainName -> SenderDomainConfiguration(
        "hmrc",
        "hmrc-email-renderer",
        imiConnector,
        MailgunApiKeys("apiKey", "publicApiKey"),
        ImiApiConfig("", ""),
        None,
        DefaultQueueConfiguration.apply(None, None),
        UrgentQueueConfiguration.apply(None),
        BackgroundQueueConfiguration.apply(None, None),
        BouncesConfiguration.apply(None),
        EventsConfiguration.apply(None)
      )
  }
}

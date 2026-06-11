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

package uk.gov.hmrc.email.services

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

import javax.inject.{ Inject, Singleton }
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.clusterworkthrottling.{ ServiceInstances, ThrottledWorkItemProcessor }
import uk.gov.hmrc.email.connectors.{ EmailRendererConnector, ImiConnectors, MailgunConnectors, PreSendingCheckConnector }
import uk.gov.hmrc.email.model.{ ImiConfiguration, RecipientDomainPattern }
import uk.gov.hmrc.email.repositories.*
import uk.gov.hmrc.email.utils.Encryption
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.ExecutionContext

@Singleton
class OutboxFactory @Inject() (
  actorSystem: ActorSystem,
  auditConnector: AuditConnector,
  configuration: Configuration,
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  preSendingCheckConnector: PreSendingCheckConnector,
  mailgunConnectors: MailgunConnectors,
  imiConnectors: ImiConnectors,
  emailEventsRepository: EmailEventsRepository,
  serviceInstances: ServiceInstances,
  encryption: Encryption,
  mongo: MongoComponent
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging {

  def create(
    senderDomain: String,
    conf: SenderDomainConfiguration,
    queueConfiguration: QueueConfiguration,
    defaults: SenderDomainDefaultsConfiguration
  ): Outbox = {

    val emailTemplateRendererConnector =
      new EmailRendererConnector(conf.renderer, servicesConfig, httpClient)
    val collection = collectionName(senderDomain, queueConfiguration)
    val emailRepository =
      EmailQueueRepository(collection, configuration, mongo)
    val mailgunConnector = mailgunConnectors.all(senderDomain)
    val imiConnector = imiConnectors.all(senderDomain)
    val notifyUrl = configuration
      .getOptional[String]("imi.notifyUrl")
      .getOrElse(throw new RuntimeException("notifyUrl configuration for imi is missing"))
    val imiConfiguration = ImiConfiguration(conf.imiConnector, conf.imi.groupId, notifyUrl)
    val holdList =
      conf.holdList.getOrElse(List.empty).map(RecipientDomainPattern.from)
    val allowList = defaults.doNotUseInProductionEmailDomainAllowList
      .getOrElse(List.empty)
      .map(RecipientDomainPattern.from)
    val throttler: ThrottledWorkItemProcessor =
      new ThrottledWorkItemProcessor(collection, actorSystem, queueConfiguration.rate) {
        def instanceCount: Int = serviceInstances.instanceCount
      }

    val domainName = conf.name
    val queue = new Queue(emailRepository)
    val sendToImi = new SendToImi(
      senderDomain = senderDomain,
      domainName = domainName,
      encryption = encryption,
      imiConfiguration = imiConfiguration,
      emailRepository = emailRepository,
      emailEventsRepository = emailEventsRepository,
      imiConnector = imiConnector,
      auditConnector = auditConnector,
      preSendingCheck = preSendingCheckConnector,
      holdList = holdList,
      allowList = allowList,
      throttler = throttler,
      new EmailStatsRepository(mongo, configuration)
    )
    val sendToMailgun = new SendToMailgun(
      senderDomain = senderDomain,
      encryption = encryption,
      imiConfiguration = imiConfiguration,
      emailRepository = emailRepository,
      mailgunConnector = mailgunConnector,
      auditConnector = auditConnector,
      preSendingCheck = preSendingCheckConnector,
      holdList = holdList,
      allowList = allowList,
      throttler = throttler
    )

    logger.warn(
      s"Starting outbox. senderDomain: $senderDomain, " +
        s"collectionName: $collection, " +
        s"holdList: ${holdList.mkString("[", ", ", "]")}, " +
        s"throttling rate: ${queueConfiguration.rate.fold("none")(rate => rate.toString())}"
    )

    Outbox(
      senderDomain,
      domainName,
      queueConfiguration.name,
      encryption,
      imiConfiguration,
      emailTemplateRendererConnector,
      emailRepository,
      emailEventsRepository,
      mailgunConnector,
      imiConnector,
      auditConnector,
      preSendingCheckConnector,
      holdList,
      allowList,
      throttler,
      queue,
      sendToImi,
      sendToMailgun
    )

  }

  def collectionName(senderDomain: String, queueConfiguration: QueueConfiguration): String =
    queueConfiguration.collection.getOrElse(s"${senderDomain}_${queueConfiguration.name}")

}

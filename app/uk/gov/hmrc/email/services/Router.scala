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

import play.api.Logging
import javax.inject.{ Inject, Singleton }
import uk.gov.hmrc.email.config.{ SenderDomainConfigurationLoader, SenderDomainDefaultsConfigurationLoader }
import uk.gov.hmrc.email.connectors.*
import uk.gov.hmrc.email.controllers.model.SendEmailRequest
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.utils.Encryption
import scala.concurrent.ExecutionContext
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.client.HttpClientV2
import scala.concurrent.Future

@Singleton
class Routers @Inject() (
  outboxFactory: OutboxFactory,
  senderDomainConfigurationLoader: SenderDomainConfigurationLoader,
  senderDomainDefaultsConfigurationLoader: SenderDomainDefaultsConfigurationLoader,
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  encryption: Encryption
)(implicit ec: ExecutionContext) {

  val all: Map[String, Router] = senderDomainConfigurationLoader.default.map { case (senderDomain, config) =>
    senderDomain ->
      new Router(
        outboxFactory,
        senderDomain,
        config,
        senderDomainDefaultsConfigurationLoader.default,
        servicesConfig,
        httpClient,
        encryption
      )
  }

  def apply(domain: String): Option[Router] = all.get(domain)
}

class Router(
  outboxFactory: OutboxFactory,
  senderDomain: String,
  senderDomainConfiguration: SenderDomainConfiguration,
  defaults: SenderDomainDefaultsConfiguration,
  servicesConfig: ServicesConfig,
  httpClient: HttpClientV2,
  encryption: Encryption
)(implicit ec: ExecutionContext)
    extends Logging {

  val senderDomainName: String = senderDomainConfiguration.name

  lazy val emailRendererConnector: EmailRendererConnector =
    new EmailRendererConnector(senderDomainConfiguration.renderer, servicesConfig, httpClient)

  lazy val urgentOutbox: Outbox =
    outboxFactory.create(senderDomain, senderDomainConfiguration, senderDomainConfiguration.urgentQueue, defaults)

  lazy val defaultOutbox: Outbox =
    outboxFactory.create(senderDomain, senderDomainConfiguration, senderDomainConfiguration.defaultQueue, defaults)

  lazy val backgroundOutbox: Outbox =
    outboxFactory.create(senderDomain, senderDomainConfiguration, senderDomainConfiguration.backgroundQueue, defaults)

  def outboxes: Seq[Outbox] = Seq(urgentOutbox, backgroundOutbox, defaultOutbox)

  def store(request: SendEmailRequest)(implicit hc: HeaderCarrier): Future[Either[ErrorMessage, Unit]] = {
    logger.warn(s"store before calling renderer")
    emailRendererConnector
      .render(request.templateId, request.parameters, request.to)
      .flatMap {
        case Right((priority, result)) =>
          logger.warn(s"store after calling renderer with ${result.subject}")
          val queuedEmailRequest =
            QueuedEmailRequest.from(request, result, encryption)
          request.alertQueue match {
            case Some(x) if Priority.isPriority(x.toLowerCase) =>
              urgentOutbox.store(queuedEmailRequest)
            case Some(x) if Priority.isBackground(x.toLowerCase) =>
              backgroundOutbox.store(queuedEmailRequest)
            case Some(x) if Priority.isDefault(x.toLowerCase) =>
              defaultOutbox.store(queuedEmailRequest)
            case None if Priority.isPriority(priority) =>
              urgentOutbox.store(queuedEmailRequest)
            case None if Priority.isBackground(priority) =>
              backgroundOutbox.store(queuedEmailRequest)
            case _ => defaultOutbox.store(queuedEmailRequest)
          }

        case Left(error) => Future.successful(Left(error))
      }
  }

}

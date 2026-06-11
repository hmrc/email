/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.services.SenderDomainConfiguration
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
case class MailgunConnectors @Inject() (
  servicesConfig: ServicesConfig,
  senderDomainConfigurationLoader: SenderDomainConfigurationLoader,
  actorSystem: ActorSystem,
  httpClient: HttpClientV2,
  auditing: HttpAuditing,
  conf: Configuration
)(implicit ec: ExecutionContext) {

  def baseUrl(serviceName: String): String = servicesConfig.baseUrl(serviceName)

  def serviceDomainConfiguration: Map[String, SenderDomainConfiguration] =
    senderDomainConfigurationLoader.default

  lazy val default: MailgunConnector = all("hmrc")

  lazy val all: Map[String, MailgunConnector] = serviceDomainConfiguration.map {
    case (senderDomain, senderDomainConfiguration) =>
      val mailgunConnector = new MailgunConnector(senderDomainConfiguration, httpClient, baseUrl("mailgun"))
      senderDomain -> mailgunConnector
  }
}

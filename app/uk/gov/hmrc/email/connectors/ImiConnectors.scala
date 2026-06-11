/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
class ImiConnectors @Inject() (
  servicesConfig: ServicesConfig,
  senderDomainConfigurationLoader: SenderDomainConfigurationLoader,
  httpClient: HttpClientV2,
  audit: AuditConnector
)(implicit ec: ExecutionContext) {

  def baseUrl(serviceName: String): String = servicesConfig.baseUrl(serviceName)

  val consentKey: String = servicesConfig.getString("imi.consentServiceKey")

  lazy val all: Map[String, ImiConnector] = senderDomainConfigurationLoader.default.map {
    case (senderDomain, senderDomainConfiguration) =>
      val mailgunConnector =
        new ImiConnector(
          senderDomainConfiguration,
          httpClient,
          consentKey,
          baseUrl("imi"),
          baseUrl("imi-consent"),
          audit
        )
      senderDomain -> mailgunConnector
  }
}

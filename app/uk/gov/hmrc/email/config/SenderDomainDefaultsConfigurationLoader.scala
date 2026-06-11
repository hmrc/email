/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import com.typesafe.config.Config

import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import uk.gov.hmrc.email.services.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

@Singleton
class SenderDomainDefaultsConfigurationLoader @Inject() (configuration: Configuration) {

  def load(configuration: Config): SenderDomainDefaultsConfiguration = {
    val allowList: Option[List[String]] = Try(
      configuration.getStringList("doNotUseInProductionEmailDomainAllowList").asScala.toList
    ).toOption
    val correctedAllowList: Option[List[String]] = allowList match {
      case Some(list) if list.isEmpty => None
      case value                      => value
    }

    SenderDomainDefaultsConfiguration(correctedAllowList)
  }

  lazy val default: SenderDomainDefaultsConfiguration = load(configuration.underlying)
}

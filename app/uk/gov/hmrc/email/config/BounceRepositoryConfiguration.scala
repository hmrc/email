/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import com.typesafe.config.Config
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import scala.concurrent.duration.{ Duration, SECONDS }

final case class BounceRepositoryConfiguration(expiry: Duration)

@Singleton
class BounceRepositoryConfigurationLoader @Inject() (configuration: Configuration) {

  def load(configuration: Config): BounceRepositoryConfiguration = {
    val bounceExpiry: Duration = Duration(configuration.getDuration(s"bounce.expiry").getSeconds, SECONDS)
    BounceRepositoryConfiguration(bounceExpiry)
  }

  lazy val default: BounceRepositoryConfiguration =
    load(configuration.underlying)

}

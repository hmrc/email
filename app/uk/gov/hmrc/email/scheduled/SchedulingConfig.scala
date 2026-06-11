/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.scheduled

import play.api.Configuration

trait SchedulingConfig {

  import scala.concurrent.duration._

  val configuration: Configuration
  val configKey: String

  private def durationFromConfig(propertyKey: String): FiniteDuration = {
    val key = s"scheduling.$configKey.$propertyKey"
    configuration
      .getOptional[FiniteDuration](key)
      .getOrElse(throw new IllegalStateException(s"Config key $key missing"))
  }

  lazy val initialDelay: FiniteDuration = durationFromConfig("initialDelay")
  lazy val interval: FiniteDuration = durationFromConfig("interval")
}

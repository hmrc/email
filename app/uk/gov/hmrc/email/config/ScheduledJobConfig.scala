/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import play.api.Configuration

import java.time.LocalTime
import scala.concurrent.duration.{ Duration, FiniteDuration }

class ScheduledJobConfig(configuration: Configuration, val name: String) {

  lazy val initialDelay: FiniteDuration =
    configuration.get[FiniteDuration](s"scheduling.$name.initialDelay")

  lazy val interval: FiniteDuration =
    configuration.get[FiniteDuration](s"scheduling.$name.interval")

  lazy val taskEnabled: Boolean =
    true

  lazy val releaseLockAfter: Duration =
    configuration
      .getOptional[FiniteDuration](s"scheduling.$name.releaseLockAfter")
      .getOrElse(Duration("1 hour"))

  lazy val activePeriodStart: LocalTime =
    LocalTime.parse(
      configuration.get[String](s"scheduling.$name.activePeriod.start")
    )

  lazy val activePeriodStop: LocalTime =
    LocalTime.parse(
      configuration.get[String](s"scheduling.$name.activePeriod.stop")
    )

  val lockId: String = s"$name-scheduled-job-lock"
}

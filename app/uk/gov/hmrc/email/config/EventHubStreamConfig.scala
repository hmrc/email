/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import scala.concurrent.duration.FiniteDuration

case class EventHubStreamConfig(
  eventPollingInterval: FiniteDuration,
  eventMaxRetries: Int,
  elements: Int,
  per: FiniteDuration,
  minBackOff: FiniteDuration,
  maxBackOff: FiniteDuration
)

object EventHubStreamConfig {
  val RandomFactor: Double = 0.2
}

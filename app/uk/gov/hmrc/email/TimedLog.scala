/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import play.api.Logger
import java.time.{ Duration, Instant }

sealed trait Level
case object Debug extends Level
case object Warning extends Level

object TimedLog {

  def apply[L <: Logger, R](logger: L, msg: String = "no additional message", level: Level = Debug)(block: => R): R = {
    val t0 = Instant.now()
    val result = block
    val t1 = Instant.now()

    val message = s"Elapsed time: ${format(Duration.between(t0, t1))}, message: $msg"
    level match {
      case Debug   => logger.debug(message)
      case Warning => logger.warn(message)
    }

    result
  }

  def format(d: Duration): String = {
    val seconds = d.toMillis / 1000
    val millis = d.toMillis % 1000
    f"$seconds%d.$millis%03d seconds"
  }
}

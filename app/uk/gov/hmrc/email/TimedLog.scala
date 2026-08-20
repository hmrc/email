/*
 * Copyright 2026 HM Revenue & Customs
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

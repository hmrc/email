/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.email.scheduled

import play.api.Configuration
import uk.gov.hmrc.email.SpecBase

import scala.concurrent.duration.{ Duration, SECONDS }

class SchedulingConfigSpec extends SpecBase {

  "initialDelay" should {
    "return correct duration" in new Setup {
      schedulingConfig().initialDelay mustBe Duration(1, SECONDS)
    }

    "throw IllegalStateException for unknown config key" in new Setup {
      intercept[IllegalStateException] {
        schedulingConfig("unknown").initialDelay
      }.getMessage must be("Config key scheduling.unknown.initialDelay missing")
    }
  }

  "interval" should {
    "return correct duration" in new Setup {
      schedulingConfig().interval mustBe Duration(1, SECONDS)
    }

    "throw IllegalStateException for unknown config key" in new Setup {
      intercept[IllegalStateException] {
        schedulingConfig("unknown").interval
      }.getMessage must be("Config key scheduling.unknown.interval missing")
    }
  }

  trait Setup {

    val config: Configuration = Configuration(
      s"scheduling.serviceInstanceCounter.taskEnabled"        -> true,
      s"scheduling.serviceInstanceCounter.initialDelay"       -> "1second",
      s"scheduling.serviceInstanceCounter.interval"           -> "1second",
      s"scheduling.serviceInstanceCounter.releaseLockAfter"   -> "1minute",
      s"scheduling.serviceInstanceCounter.activePeriod.start" -> "08:00",
      s"scheduling.serviceInstanceCounter.activePeriod.stop"  -> "23:00"
    )

    def schedulingConfig(configKeyName: String = "serviceInstanceCounter"): SchedulingConfig = new SchedulingConfig {
      override val configuration: Configuration = config
      override val configKey: String = configKeyName
    }
  }
}

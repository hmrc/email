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

package uk.gov.hmrc.email.config

import play.api.Configuration
import uk.gov.hmrc.email.SpecBase

import java.time.LocalTime
import scala.concurrent.duration.{ Duration, HOURS, MINUTES, SECONDS }

class ScheduledJobConfigSpec extends SpecBase {

  "initialDelay" should {
    "return correct duration" in new Setup {
      scheduledJobConfig.initialDelay mustBe Duration(1, SECONDS)
    }
  }

  "interval" should {
    "return correct duration" in new Setup {
      scheduledJobConfig.initialDelay mustBe Duration(1, SECONDS)
    }
  }

  "taskEnabled" should {
    "return true" in new Setup {
      scheduledJobConfig.taskEnabled mustBe true
    }
  }

  "releaseLockAfter" should {
    "return correct duration" in new Setup {
      scheduledJobConfig.releaseLockAfter mustBe Duration(1, MINUTES)
      scheduledJobConfigWithoutReleaseLock.releaseLockAfter mustBe Duration(1, HOURS)
    }
  }

  "activePeriodStart" should {
    "return correct time" in new Setup {
      val hour = 8
      val minute = 0

      scheduledJobConfig.activePeriodStart mustBe LocalTime.of(hour, minute)
    }
  }

  "activePeriodStop" should {
    "return correct time" in new Setup {
      val hour = 23
      val minute = 0

      scheduledJobConfig.activePeriodStop mustBe LocalTime.of(hour, minute)
    }
  }

  "lockId" should {
    "return correct value" in new Setup {
      scheduledJobConfig.lockId mustBe "serviceInstanceCounter-scheduled-job-lock"
    }
  }

  trait Setup {
    val propertyName = "serviceInstanceCounter"

    val config: Configuration = Configuration(
      s"scheduling.$propertyName.initialDelay"       -> "1second",
      s"scheduling.$propertyName.interval"           -> "1second",
      s"scheduling.$propertyName.releaseLockAfter"   -> "1minute",
      s"scheduling.$propertyName.activePeriod.start" -> "08:00",
      s"scheduling.$propertyName.activePeriod.stop"  -> "23:00",
      s"$propertyName-scheduled-job-lock"            -> "test"
    )

    val configWithoutReleaseLock: Configuration = Configuration(
      s"scheduling.$propertyName.initialDelay"       -> "1second",
      s"scheduling.$propertyName.interval"           -> "1second",
      s"scheduling.$propertyName.activePeriod.start" -> "08:00",
      s"scheduling.$propertyName.activePeriod.stop"  -> "23:00"
    )

    val scheduledJobConfig = new ScheduledJobConfig(config, propertyName)
    val scheduledJobConfigWithoutReleaseLock = new ScheduledJobConfig(configWithoutReleaseLock, propertyName)
  }
}

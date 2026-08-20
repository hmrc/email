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

package uk.gov.hmrc.email.utils

sealed trait EmailStatus {
  def name: String
}
object EmailStatus {
  case object Sent extends EmailStatus {
    val name = "sent"
  }
  case object Accepted extends EmailStatus {
    val name = "accepted"
  }
  case object Bounced extends EmailStatus {
    val name = "bounced"
  }
  case object Delivered extends EmailStatus {
    val name = "delivered"
  }
  case object Opened extends EmailStatus {
    val name = "opened"
  }
  case object TemporaryBounce extends EmailStatus {
    val name = "temporary_bounce"
  }
  case object Complained extends EmailStatus {
    val name = "complained"
  }
}

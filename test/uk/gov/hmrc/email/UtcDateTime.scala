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

import java.time.temporal.ChronoUnit
import java.time.{ Instant, LocalDateTime, ZoneOffset }

object UtcDateTime {

  private val utc = ZoneOffset.UTC
  def apply(year: Int, month: Int, day: Int, hour: Int, minute: Int): Instant = {

    val dateTime: LocalDateTime = LocalDateTime.of(year, month, day, hour, minute)
    dateTime.toInstant(utc)
  }
  def now: Instant = Instant.now()
  def fromMillis(millis: Long): Instant = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.MILLIS)

  implicit val utcDateTimeOrdering: Ordering[Instant] =
    Ordering.fromLessThan((a: Instant, b: Instant) => a.isBefore(b))
}

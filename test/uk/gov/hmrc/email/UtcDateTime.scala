/*
 * Copyright 2023 HM Revenue & Customs
 *
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

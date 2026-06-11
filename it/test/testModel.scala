/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import java.util.UUID
import TestEvent.defaultTags
import play.api.libs.json._
import uk.gov.hmrc.email.model.Tag
import uk.gov.hmrc.email.utils.DateTimeUtils

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

case class TestBounce(emailAddress: String, detected: Instant, code: Option[Int], emailSource: Option[String])

object TestBounce {

  import play.api.libs.functional.syntax._

  implicit val bounceRead: Reads[TestBounce] = (
    (__ \ "emailAddress").read[String] and
      (__ \ "detected").read[String].map(d => Instant.parse(d)) and
      (__ \ "code").readNullable[Int] and
      (__ \ "emailSource").readNullable[String]
  )(TestBounce.apply)
}

case class TestEvent(
  time: Instant,
  address: String,
  code: Option[Int],
  messageId: String = UUID.randomUUID().toString,
  event: String = "failed",
  severity: Option[String],
  tags: Seq[Tag] = defaultTags
) {
  def toBounce = TestBounce(address, time, code, None)
}

object TestEvent {

  val isBounced: TestEvent => Boolean = e => Set("failed", "rejected")(e.event)
  val isPermanent: TestEvent => Boolean = e => e.severity.contains("permanent")

  implicit val format: OFormat[TestEvent] = Json.format[TestEvent]

  private val now = DateTimeUtils.now.truncatedTo(ChronoUnit.MILLIS)

  val defaultTags: Seq[Tag] =
    Seq("regime.sa", "template_SA_309", "mdtp").map(Tag.apply(_))

  def generateBounce(i: Int): TestEvent =
    TestEvent(
      time = now.minusSeconds(i),
      address = s"test-$i@test.com",
      code = Some(500 + i),
      severity = Some("permanent")
    )

  def generateOpened(i: Int): TestEvent =
    TestEvent(time = now.minusSeconds(i), address = s"test-$i@test.com", code = None, event = "opened", severity = None)
}

case class TestEvents(events: Seq[TestEvent]) {
  import TestEvent.{ isBounced, isPermanent }
  def bounces = events.filter(e => isBounced(e) && isPermanent(e))
  def openedEvents: TestEvents = TestEvents(events.filter(_.event == "opened"))
}

object TestEvents {
  implicit val format: OFormat[TestEvents] = Json.format[TestEvents]
}

case class TestEmail(from: String, to: String, subject: String, plainTextBody: String)

object TestEmail {
  implicit val format: OFormat[TestEmail] = Json.format[TestEmail]
}

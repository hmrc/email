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

package uk.gov.hmrc.email.repositories

import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import play.api.test.Helpers.*
import uk.gov.hmrc.email.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.model.EventType.*
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class EventsAccessRepositorySpec
    extends SpecBase with DefaultPlayMongoRepositorySupport[EventAccess] with ScalaFutures with LoneElement
    with IntegrationPatience {

  private val senderDomain = "exampleDomain"
  private val otherSenderDomain = "anotherDomain"

  val repository: EventsAccessRepository = new EventsAccessRepository(mongoComponent)
  override protected def checkTtlIndex: Boolean = false

  override def beforeEach(): Unit = {
    super.beforeEach()
    val _ = await(repository.collection.deleteMany(Filters.empty()).toFuture())
  }

  "last accessed timestamp" should {
    val someTime = UtcDateTime(2011, 1, 1, 8, 40)
    val later = someTime.plusSeconds(1)
    val laterStill: Instant = someTime.plusSeconds(2)
    val event1 = MailgunEvent(
      "event id",
      EmailAddress("me@me.com"),
      someTime,
      None,
      None,
      Some(MailgunId("mid1")),
      PermanentBounce,
      None,
      None,
      Map("enrolment" -> "encryptedValue"),
      None,
      None
    )
    val event2 = event1.copy(detected = laterStill)
    val event3 = event1.copy(detected = later)

    "be none if never persisted" in {
      repository.getLastAccessed(senderDomain).futureValue mustBe None
    }

    "be the previously persisted value" in {
      val eventAccessTime = Instant.ofEpochMilli(UtcDateTime(2011, 1, 1, 8, 40).toEpochMilli)

      await(
        repository.collection
          .insertOne(EventAccess(eventAccessTime, s"${senderDomain}_bouncesLastAccessed"))
          .toFuture()
      )

      repository.getLastAccessed(senderDomain).futureValue.get mustBe eventAccessTime
    }

    "be recorded for most recent event" in {
      val expectedInstant = Instant.ofEpochMilli(laterStill.toEpochMilli)
      await(repository.saveLatestDetected(senderDomain, Seq(event1, event2, event3)))

      repository.getLastAccessed(senderDomain).futureValue.get mustBe expectedInstant
    }

    "be recorded when already an existing event with the same id" in {
      val expectedInstant = Instant.ofEpochMilli(laterStill.toEpochMilli)
      await(repository.saveLatestDetected(senderDomain, Seq(event2)))
      await(repository.saveLatestDetected(senderDomain, Seq(event1, event2, event3)))

      repository.getLastAccessed(senderDomain).futureValue must contain(expectedInstant)
    }

    "not be recorded if there are no bounces" in {
      await(repository.saveLatestDetected(senderDomain, Seq()))

      repository.getLastAccessed(senderDomain).futureValue mustBe None
    }

    "return the most recent event for the sender domain provided" in {
      val eventAccessTimeForSenderDomain = UtcDateTime(2011, 1, 1, 8, 40)
      val eventAccessTimeForOtherDomain = UtcDateTime(2011, 1, 1, 9, 0)

      val eventExampleDomain =
        event1.copy(detected = eventAccessTimeForSenderDomain)
      val eventOtherDomain =
        event1.copy(detected = eventAccessTimeForOtherDomain)

      await(repository.saveLatestDetected(senderDomain, Seq(eventExampleDomain)))
      await(repository.saveLatestDetected(otherSenderDomain, Seq(eventOtherDomain)))

      repository.getLastAccessed(senderDomain).futureValue must contain(
        Instant.ofEpochMilli(eventAccessTimeForSenderDomain.toEpochMilli)
      )
      repository.getLastAccessed(otherSenderDomain).futureValue must contain(
        Instant.ofEpochMilli(eventAccessTimeForOtherDomain.toEpochMilli)
      )
    }

    "return none if last time not found" in {
      repository.getLastAccessed(senderDomain).futureValue mustBe None
    }

    "return none if no value found for sender domain or without any sender domain" in {
      val eventAccessTimeForOtherDomain = UtcDateTime(2011, 1, 1, 9, 0)

      val eventOtherDomain =
        event1.copy(detected = eventAccessTimeForOtherDomain)

      await(repository.saveLatestDetected(otherSenderDomain, Seq(eventOtherDomain)))

      repository.getLastAccessed(senderDomain).futureValue mustBe None
      repository.getLastAccessed(otherSenderDomain).futureValue must contain(
        Instant.ofEpochMilli(eventAccessTimeForOtherDomain.toEpochMilli)
      )
    }
  }

}

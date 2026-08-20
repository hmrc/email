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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mongodb.scala.bson.BsonBoolean
import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture, documentToUntypedDocument }
import org.mongodb.scala.model.Filters
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.{ Inspectors, LoneElement }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.libs.json.{ JsResultException, Json }
import play.api.{ ConfigLoader, Configuration }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_ADDRESS_VALUE, TEST_EVENT_TYPE, TEST_ID, TEST_MESSAGE_ID, TEST_RANDOM_UUID, TEST_REASON, TEST_TIME_INSTANT }
import uk.gov.hmrc.email.model.{ DuplicateEventHubItem, ItemSaved, Tag }
import uk.gov.hmrc.email.util.QueuedEmailRequestGenerator
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ InProgress, ToDo }
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.time.{ Duration, Instant }
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class EventHubRepositorySpec
    extends SpecBase with DefaultPlayMongoRepositorySupport[WorkItem[EventHubItem]] with ScalaFutures with Inspectors
    with LoneElement with QueuedEmailRequestGenerator {
  private val now: Instant = Instant.now()
  private val eventId = UUID.randomUUID()
  private val eventId2 = UUID.randomUUID()
  private val mockConfiguration = mock[Configuration]
  when(mockConfiguration.getOptional[Int](any[String])(any[ConfigLoader[Int]])).thenReturn(None)

  override val repository: EventHubRepository = new EventHubRepository("event_hub", mockConfiguration, mongoComponent) {
    override lazy val inProgressRetryAfter = Duration.ofHours(1)
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(60, Seconds)),
      interval = scaled(Span(150, Millis))
    )

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    repository.collection.deleteMany(Filters.empty()).toFuture().futureValue
    val _ = repository.ensureIndexes().futureValue
  }

  val evt =
    EventHubItem(
      "eventId1",
      eventId,
      "test@gmail.com",
      now,
      "failed",
      "some reason",
      Map("enrolment" -> "some enrolment"),
      Some(500),
      "mailgun id",
      None
    )
  val evt2 =
    EventHubItem(
      "eventId2",
      eventId2,
      "test2@gmail.com",
      now,
      "failed",
      "some reason",
      Map("enrolment" -> "some enrolment"),
      Some(500),
      "mailgun id",
      Some(Tag("template-id"))
    )

  "The event hub repository" should {
    "ensure indexes are created" in {
      val defaultExpireSecondsTTL = 259200
      val indexesList = repository.collection.listIndexes().toFuture().futureValue
      indexesList.size mustBe 6

      val hashIndex = indexesList.find(_.getString("name") == "hashIndex").get
      hashIndex.get("unique").get mustBe BsonBoolean(true)
      val createdAtIndex = indexesList.find(_.getString("name") == "createdAtIndex").get
      createdAtIndex.get("background").get mustBe BsonBoolean(true)
      createdAtIndex.get("expireAfterSeconds").get.asNumber().intValue() mustBe defaultExpireSecondsTTL
    }

    "be able to save a EventHubItem" in {
      repository.pushNewMailgunEventHubItems(Seq(evt)).futureValue.head.item must be(evt)
    }

    "be able to return DuplicateEventHubItem for duplicate item" in {
      repository.pushEventHubItem(evt).futureValue mustBe ItemSaved
      repository.pushEventHubItem(evt).futureValue mustBe DuplicateEventHubItem
    }

    "be able to retrieve a EventHubItem" in {
      val e = (for {
        _ <- repository.pushNewMailgunEventHubItems(Seq(evt))
        e <- repository.pullOutstandingEventHubItem
      } yield e).futureValue
      e.isEmpty must be(false)
      e.get.status must be(InProgress)
      e.get.item.eventId must be(eventId)
    }

    "be able to fail a EventHubItem" in {
      val e = (for {
        _  <- repository.pushNewMailgunEventHubItems(Seq(evt))
        e  <- repository.pullOutstandingEventHubItem
        fe <- repository.failEventHubItem(e.get)
      } yield fe).futureValue
      e must be(true)
    }

    "not be able to add duplicate EventHubItems" in {
      val e = (for {
        _ <- repository.pushNewMailgunEventHubItems(Seq(evt, evt2, evt))
        c <- repository.count(ToDo)
      } yield c).futureValue
      e must be(2)
    }
  }

  "EventHubItem.fmt" should {
    "read the json correctly" in new Setup {
      Json.parse(eventHubItemJsonString).as[EventHubItem] mustBe eventHubItem
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(eventHubItemInvalidJsonString).as[EventHubItem]
      }
    }

    "write the object correctly" in new Setup {
      Json.toJson(eventHubItem) mustBe Json.parse(eventHubItemJsonString)
    }
  }

  trait Setup {
    val eventHubItem: EventHubItem = EventHubItem(
      id = TEST_ID,
      eventId = TEST_RANDOM_UUID,
      emailAddress = TEST_EMAIL_ADDRESS_VALUE,
      detected = TEST_TIME_INSTANT,
      eventType = TEST_EVENT_TYPE,
      reason = TEST_REASON,
      tags = Map(),
      code = Some(1),
      messageId = TEST_MESSAGE_ID,
      template = None
    )

    val eventHubItemJsonString: String =
      """{
        |"id":"test_id",
        |"eventId":"00000000-0000-0000-0000-000000000000",
        |"emailAddress":"test@test.com",
        |"detected":{"$date":{"$numberLong":"65478234"}},
        |"eventType":"test_event",
        |"reason":"test_reason",
        |"tags":{},
        |"code":1,
        |"messageId":"1fghj234578999#uytre",
        |"hash":"TRS6mNESNhUPP1q6iKkyOnxBIoMt8b+MwQTg7sze8lc="}""".stripMargin

    val eventHubItemInvalidJsonString: String =
      """{
        |"eventId":"00000000-0000-0000-0000-000000000000",
        |"emailAddress":"test@test.com",
        |"detected":{"$date":{"$numberLong":"65478234"}},
        |"eventType":"test_event",
        |"reason":"test_reason",
        |"tags":{},
        |"code":1,
        |"messageId":"1fghj234578999#uytre",
        |"hash":"TRS6mNESNhUPP1q6iKkyOnxBIoMt8b+MwQTg7sze8lc="}""".stripMargin
  }
}

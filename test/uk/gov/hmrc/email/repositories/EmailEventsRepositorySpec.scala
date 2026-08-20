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

import com.typesafe.config.ConfigFactory
import org.mongodb.scala.{ MongoWriteException, ObservableFuture, SingleObservableFuture }
import org.mongodb.scala.model.Filters
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import play.api.Configuration
import play.api.test.Helpers.{ await, defaultAwaitTimeout }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.model.EventType
import uk.gov.hmrc.email.model.EventType.{ Delivered, Opened, Sent }
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

class EmailEventsRepositorySpec
    extends SpecBase with DefaultPlayMongoRepositorySupport[WorkItem[EmailEventsItem]] with ScalaFutures
    with LoneElement with IntegrationPatience {
  self =>

  override def checkTtlIndex: Boolean = false

  private val messageId = UUID.randomUUID()

  private val underlyingConfig = ConfigFactory.load()
  private val configuration = Configuration(underlyingConfig).withFallback(
    Configuration.from(
      Map(
        "senderDomains.domain1.name"                 -> "tax.service.gov.uk",
        "senderDomains.domain1.renderer"             -> "",
        "senderDomains.domain1.imiConnector"         -> "true",
        "senderDomains.domain1.mailgun.apiKey"       -> "exampleApiKey",
        "senderDomains.domain1.mailgun.publicApiKey" -> "examplepublicApiKey",
        "senderDomains.domain1.imi.apiKey"           -> "exampleApiKey",
        "senderDomains.domain1.imi.groupId"          -> "sampleGroupId"
      )
    )
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
    await(repository.collection.deleteMany(Filters.empty()).toFuture())
    val _ = await(repository.ensureIndexes())
  }

  override protected val repository: EmailEventsRepository =
    new EmailEventsRepository(configuration, mongoComponent)

  "markSent function" should {
    "create new EventItem with Sent" in {
      val sampleInstant = Instant.parse("2023-02-02T00:00:00.00Z")
      await(repository.markSent(messageId.toString, Some("testUrl"), None, "", sampleInstant, sampleInstant))

      val retrieved =
        repository.collection.find().toFuture().futureValue.loneElement
      retrieved must have(Symbol("status")(ToDo), Symbol("availableAt")(sampleInstant))
      retrieved.item mustBe EmailEventsItem(messageId.toString, Some("testUrl"), Map(Sent -> sampleInstant), None, "")
    }

    "fail on duplicate messageId" in {
      await(repository.markSent(messageId.toString, None, senderDomain = "tax.service.gov.uk"))

      val ex = intercept[MongoWriteException] {
        await(repository.markSent(messageId.toString, None, senderDomain = "tax.service.gov.uk"))
      }

      ex.getError.getMessage must include("E11000 duplicate key error")
    }

    "findEvent function" should {
      "get event by messageId" in {
        await(repository.markSent(messageId.toString, None, senderDomain = "tax.service.gov.uk"))
        await(repository.markSent(UUID.randomUUID().toString, None, senderDomain = "tax.service.gov.uk"))
        repository.findEvent(messageId.toString).futureValue.get.item.messageId mustBe messageId.toString
      }
    }

    "markEvent function" should {
      "update eventItem with eventType" in {
        val insertInstant = Instant.parse("2023-02-02T00:00:00.00Z")
        val updateInstant = Instant.parse("2023-02-03T00:00:00.00Z")
        await(
          repository.markSent(
            messageId.toString,
            Some("testUrl"),
            None,
            senderDomain = "tax.service.gov.uk",
            insertInstant,
            insertInstant
          )
        )

        await(repository.markEvent(messageId.toString, EventType.Delivered, updateInstant))
        await(repository.markEvent(messageId.toString, EventType.Opened, updateInstant))

        val retrieved =
          repository.collection.find().toFuture().futureValue.loneElement

        retrieved must have(Symbol("status")(ToDo), Symbol("availableAt")(insertInstant))
        retrieved.item mustBe EmailEventsItem(
          messageId.toString,
          Some("testUrl"),
          Map(Sent -> insertInstant, Delivered -> updateInstant, Opened -> updateInstant),
          None,
          "tax.service.gov.uk"
        )
      }
    }
  }

}

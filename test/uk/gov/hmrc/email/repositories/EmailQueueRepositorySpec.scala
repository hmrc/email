/*
 * Copyright 2023 HM Revenue & Customs
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

import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture }
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.model.Filters
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.{ Inspectors, LoneElement }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.test.Helpers.*
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.util.QueuedEmailRequestGenerator
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, InProgress, ToDo }
import uk.gov.hmrc.mongo.workitem.WorkItem
import java.time.temporal.ChronoUnit
import java.time.{ Duration, Instant }
import scala.concurrent.ExecutionContext.Implicits.global

class EmailQueueRepositorySpec
    extends SpecBase with DefaultPlayMongoRepositorySupport[WorkItem[QueuedEmailRequest]] with ScalaFutures
    with Inspectors with LoneElement with QueuedEmailRequestGenerator {

  override protected def checkTtlIndex: Boolean = false

  private val instant = Instant.now
  private val mockConfiguration = mock[Configuration]

  override val repository: EmailQueueRepository =
    new EmailQueueRepository("emailQueue", mockConfiguration, mongoComponent) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis

      override def now(): Instant = instant
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

  private val emailRequest = emailRequestWithTemplate("template")
  private def emailRequestWithTemplate(template: String) =
    generateAQueuedEmailRequest(templateId = template)

  "The pending email repository" should {
    "ensure indexes are created" in {
      repository.collection.listIndexes().toFuture().futureValue.size mustBe 5
    }

    "be able to save and reload a pending email" in {
      // given
      val sendEmailWorkItem1 = await(repository.enqueue(emailRequest))

      // then
      val workItem = repository.findById(sendEmailWorkItem1.id).futureValue.get
      workItem.receivedAt.toEpochMilli must be(instant.toEpochMilli)
      workItem.updatedAt.toEpochMilli must be(instant.toEpochMilli)

      workItem must have(Symbol("item")(emailRequest), Symbol("status")(ToDo))
    }

    "be able to save the same pending email twice" in {
      // given
      await(repository.enqueue(emailRequest))
      await(repository.enqueue(emailRequest))

      // then
      val loadedNotifications =
        repository.collection.find().toFuture().futureValue
      loadedNotifications must have(size(2))

      loadedNotifications.foreach { workItem =>
        workItem.receivedAt.toEpochMilli must be(instant.toEpochMilli)
        workItem.updatedAt.toEpochMilli must be(instant.toEpochMilli)
      }

      every(loadedNotifications) must have(Symbol("item")(emailRequest), Symbol("status")(ToDo))
    }

    "mark a pending email as failed" in {
      // given
      val sendEmailWorkItem1 = await(repository.enqueue(emailRequest))

      // when
      val id = sendEmailWorkItem1.id
      repository.markAs(id, Failed).futureValue must be(true)

      // then
      repository.findById(id).futureValue.get must have(
        Symbol("status")(Failed),
        Symbol("item")(sendEmailWorkItem1.item)
      )
    }

    "return false trying to fail a non-existent pending email" in {
      repository
        .markAs(new ObjectId(), Failed)
        .futureValue must be(false)
      repository.collection.find().toFuture().futureValue must be(empty)
    }

    "pull the emails in the same order as they were received" in {
      // given
      val emailRequest1 = generateAQueuedEmailRequest(templateId = "t1")
      val emailRequest2 = generateAQueuedEmailRequest(templateId = "t2")
      val emailRequest3 = generateAQueuedEmailRequest(templateId = "t3")
      await(repository.enqueue(emailRequest1))
      await(repository.enqueue(emailRequest2))
      await(repository.enqueue(emailRequest3))

      // then
      repository.pullPendingEmail(Instant.now()).futureValue.get.item must be(emailRequest1)
      repository.pullPendingEmail(Instant.now()).futureValue.get.item must be(emailRequest2)
      repository.pullPendingEmail(Instant.now()).futureValue.get.item must be(emailRequest3)
    }

    "pull ToDo pending email" in {
      // given
      await(repository.enqueue(emailRequest))

      // then
      repository.pullPendingEmail(Instant.now().plusSeconds(10)).futureValue.get must have(
        Symbol("item")(emailRequest),
        Symbol("status")(InProgress)
      )
    }

    "pull timed out Failed pending emails" in {
      // given
      val sendEmailWorkItem1 = await(repository.enqueue(emailRequest))
      repository
        .markAs(sendEmailWorkItem1.id, Failed)
        .futureValue must be(true)

      // then
      repository.pullPendingEmail(Instant.now().plusSeconds(10)).futureValue.get must have(
        Symbol("item")(emailRequest),
        Symbol("status")(InProgress)
      )
    }

    "pull nothing if no pending email exist" in {
      repository.pullPendingEmail(Instant.now()).futureValue must be(None)
    }

    "not pull in progress pending email" in {
      // given
      await(repository.enqueue(emailRequest))

      // when
      repository.pullPendingEmail(Instant.now().plusSeconds(10)).futureValue.get must have(
        Symbol("item")(emailRequest),
        Symbol("status")(InProgress)
      )

      // then
      repository.pullPendingEmail(Instant.now()).futureValue must be(None)
    }

    "not pull pending email failed after the failedBefore time" in {
      // given
      val workItem: WorkItem[QueuedEmailRequest] =
        await(repository.enqueue(emailRequest))
      repository.markAs(workItem.id, Failed).futureValue must be(true)

      // then
      repository.pullPendingEmail().futureValue must be(None)
    }

    "complete and delete a pending email if it is in progress" in {
      // given
      val sendEmailWorkItem1 = await(repository.enqueue(emailRequest))
      repository
        .markAs(sendEmailWorkItem1.id, InProgress)
        .futureValue must be(true)

      // when
      repository.complete(sendEmailWorkItem1.id).futureValue must be(true)

      // then
      repository.findById(sendEmailWorkItem1.id).futureValue mustBe None
    }

    "not complete a pending email if it is not in progress" in {
      // given
      val sendEmailWorkItem1 = await(repository.enqueue(emailRequest))

      // when
      repository.complete(sendEmailWorkItem1.id).futureValue must be(false)

      // then
      val sendEmailWorkItem2 = sendEmailWorkItem1.copy(
        receivedAt = sendEmailWorkItem1.receivedAt.truncatedTo(ChronoUnit.MILLIS),
        updatedAt = sendEmailWorkItem1.updatedAt.truncatedTo(ChronoUnit.MILLIS),
        availableAt = sendEmailWorkItem1.availableAt.truncatedTo(ChronoUnit.MILLIS)
      )

      repository.findById(sendEmailWorkItem1.id).futureValue mustBe Some(sendEmailWorkItem2)
    }

    "not complete a pending email if it cannot be found" in {
      repository.complete(new ObjectId()).futureValue must be(false)
    }
  }

  "metricsByTemplateId" should {

    "Return a map ('template' -> 1) when one email is present" in {
      repository.metricsByTemplateId().futureValue must be(empty)
      await(repository.enqueue(emailRequest))
      repository.metricsByTemplateId().futureValue must be(Map("template" -> 1))
    }

    "Return a map ('template' -> 2) when two emails are present" in {
      repository.metricsByTemplateId().futureValue must be(empty)
      await(repository.enqueue(emailRequest))
      await(repository.enqueue(emailRequest))

      repository.metricsByTemplateId().futureValue must be(Map("template" -> 2))
    }

    "Return a map ('template1' -> 1, 'template2' -> 1) when one email per template is present" in {
      repository.metricsByTemplateId().futureValue must be(empty)
      await(repository.enqueue(emailRequestWithTemplate("template1")))
      await(repository.enqueue(emailRequestWithTemplate("template2")))

      repository.metricsByTemplateId().futureValue must contain theSameElementsAs Map(
        "template1" -> 1,
        "template2" -> 1
      )
    }

    "Return a map ('template1' -> 3, 'template2' -> 2) when multiple emails per template are present" in {
      repository.metricsByTemplateId().futureValue must be(empty)
      await(repository.enqueue(emailRequestWithTemplate("template1")))
      await(repository.enqueue(emailRequestWithTemplate("template2")))
      await(repository.enqueue(emailRequestWithTemplate("template1")))
      await(repository.enqueue(emailRequestWithTemplate("template2")))
      await(repository.enqueue(emailRequestWithTemplate("template1")))

      repository.metricsByTemplateId().futureValue must contain theSameElementsAs Map(
        "template1" -> 3,
        "template2" -> 2
      )
    }
  }

}

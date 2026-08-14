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

package uk.gov.hmrc.email.services

import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Application
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import play.api.inject
import org.mockito.ArgumentMatchers.any
import org.mongodb.scala.bson.ObjectId
import play.api.test.Helpers.*
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_ADDRESS, TEST_FROM_ADDRESS, TEST_HTML, TEST_PARAMETERS_MAP, TEST_PLAIN_TEXT, TEST_SUBJECT, TEST_TEMPLATE_ID, TEST_TEMPLATE_REGIME, TEST_TIME_INSTANT, TEST_URL }
import uk.gov.hmrc.email.connectors.ErrorMessage
import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.InProgress
import uk.gov.hmrc.mongo.workitem.WorkItem

import scala.concurrent.Future

class QueueSpec extends SpecBase {

  "add" should {
    "enqueue the request successfully" when {
      "the request is of Imi" in new Setup {
        when(mockEmailRepository.enqueue(any)).thenReturn(Future.successful(workItem))

        val result: Either[ErrorMessage, Unit] = await(queue.add(queuedEmailRequest, true))

        result must be(Right(()))
      }

      "the request is not of Imi" in new Setup {
        when(mockEmailRepository.enqueue(any)).thenReturn(Future.successful(workItem))

        val result: Either[ErrorMessage, Unit] = await(queue.add(queuedEmailRequest, false))

        result must be(Right(()))
      }
    }

    "throw exception" when {
      "the request is of Imi and error occurs while enqueuing" in new Setup {
        when(mockEmailRepository.enqueue(any)).thenReturn(Future.failed(RuntimeException("connection error")))

        val result: Either[ErrorMessage, Unit] = await(queue.add(queuedEmailRequest, true))

        result must be(Left(ErrorMessage(s"Failed to queue due to connection error")))
      }

      "the request is not of Imi and error occurs while enqueuing" in new Setup {
        when(mockEmailRepository.enqueue(any)).thenReturn(Future.failed(RuntimeException("connection error")))

        val result: Either[ErrorMessage, Unit] = await(queue.add(queuedEmailRequest, false))

        result must be(Left(ErrorMessage(s"Failed to queue due to connection error")))
      }
    }
  }

  trait Setup {
    val mockEmailRepository: EmailQueueRepository = mock[EmailQueueRepository]

    val renderedResult: RenderResult =
      RenderResult(
        TEST_PLAIN_TEXT,
        TEST_HTML,
        TEST_FROM_ADDRESS,
        TEST_SUBJECT,
        TEST_TEMPLATE_REGIME,
        Some(TEST_TEMPLATE_ID)
      )

    val queuedEmailRequest: QueuedEmailRequest = QueuedEmailRequest(
      to = List(TEST_EMAIL_ADDRESS),
      templateId = TEST_TEMPLATE_ID,
      parameters = TEST_PARAMETERS_MAP,
      tags = Map.empty,
      force = true,
      eventUrl = Some(TEST_URL),
      onSendUrl = Some(TEST_URL),
      auditData = Map.empty,
      renderedEmail = Some(renderedResult)
    )

    val workItem: WorkItem[QueuedEmailRequest] =
      WorkItem(
        id = new ObjectId(),
        receivedAt = TEST_TIME_INSTANT,
        updatedAt = TEST_TIME_INSTANT,
        status = InProgress,
        failureCount = 0,
        item = queuedEmailRequest,
        availableAt = TEST_TIME_INSTANT
      )

    val app: Application = applicationBuilder
      .overrides(
        inject.bind[EmailQueueRepository].toInstance(mockEmailRepository)
      )
      .configure(
        "microservice.metrics.enabled" -> false,
        "metrics.enabled"              -> false,
        "auditing.enabled"             -> false
      )
      .build()

    val queue: Queue = app.injector.instanceOf[Queue]
  }
}

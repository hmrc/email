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

package uk.gov.hmrc.email.services

import com.typesafe.config.ConfigException
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.{ ArgumentCaptor, Mockito }
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.model.Filters
import org.mongodb.scala.result.InsertOneResult
import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture }
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ Eventually, IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers.*
import uk.gov.hmrc.clusterworkthrottling.WorkThrottling
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.email.connectors.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.repositories.*
import uk.gov.hmrc.email.repositories.model.{ EmailEventsItem, QueuedEmailRequest }
import uk.gov.hmrc.email.util.QueuedEmailRequestGenerator
import uk.gov.hmrc.email.utils.{ CorrelationId, Encryption, NonEmptyString }
import uk.gov.hmrc.email.{ FakeSenderDomainConfiguration, SpecBase }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ BadRequestException, HeaderCarrier, HttpResponse }
import uk.gov.hmrc.mongo.lock.LockRepository
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, InProgress, PermanentlyFailed }
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent

import java.net.URL
import java.time.{ Duration, Instant }
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.reflectiveCalls

class OutboxSpec
    extends SpecBase with ScalaFutures with DefaultPlayMongoRepositorySupport[WorkItem[QueuedEmailRequest]]
    with IntegrationPatience with LoneElement with Eventually {

  type Hdrs = Seq[(String, String)]
  type Body = Map[String, Seq[String]]
  type Params = Map[String, String]
  override protected def checkTtlIndex: Boolean = false
  val actorSystem: ActorSystem = ActorSystem("OutboxSpec")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)

  implicit val utcDateTimeOrdering: Ordering[Instant] =
    Ordering.fromLessThan((a: Instant, b: Instant) => a.isBefore(b))

  val configuration = mock[Configuration]

  override val repository: EmailQueueRepository =
    new EmailQueueRepository("emailQueue", configuration, mongoComponent) {
      override lazy val inProgressRetryAfter: Duration = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  override def beforeEach(): Unit =
    super.beforeEach()

  "For mailgun" when {
    "after sending an email, the record in mongo" should {
      "be removed if the request is successfully processed" in new TestCase {

        private val outbox = new TestOutbox {
          new Queue(repository)
        }.get(false)

        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest()))

        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)

        eventually {
          outbox.sendAll.futureValue must be(
            EmailQueueProcessingResults(sent = 3, requeued = 0, permanentlyFailed = 0, aborted = 0)
          )
        }

        repository.collection.find().toFuture().futureValue must be(empty)
      }

      "be kept if the request processing fails" in new TestCase {

        private val outbox = new TestOutbox {}.get(false)

        await(repository.enqueue(generateAQueuedEmailRequest()))

        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.failed(new RuntimeException("Ooops")))

        private val pendingBeforeFailure = repository.collection.find().toFuture().futureValue.loneElement

        private val results = outbox.sendAll
        results.futureValue must be(EmailQueueProcessingResults(sent = 0, requeued = 1, 0, 0))

        private val pendingAfterFailure = repository.collection.find().toFuture().futureValue.loneElement

        pendingAfterFailure must have(Symbol("status")(Failed))
        pendingAfterFailure.updatedAt.isAfter(pendingBeforeFailure.updatedAt) must be(true)
      }

      "be kept if the request processing results with unexpected Mailgun response" in new TestCase {

        private val mailgunClient = mock[HttpClientV2]
        val requestBuilder = mock[RequestBuilder]

        when(
          mailgunClient.post(any[URL])(any[HeaderCarrier])
        ).thenReturn(requestBuilder)
        when(mailgunClient.delete(any[URL])(any[HeaderCarrier])).thenReturn(requestBuilder)
        when(requestBuilder.withBody(any)(using any, any, any)).thenReturn(requestBuilder)

        when(requestBuilder.withProxy).thenReturn(requestBuilder)
        when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)
        when(requestBuilder.execute[HttpResponse](using any, any))
          .thenReturn(Future.successful(HttpResponse(OK, Json.parse("""{}"""), Map.empty[String, Seq[String]])))

        private val mailgunConnectorStub = new MailgunConnector(senderDomainConfiguration, mailgunClient, "")
        private val outbox = new TestOutbox {}.get(false).copy(mailgunConnector = mailgunConnectorStub)

        await(
          repository.enqueue(
            generateAQueuedEmailRequest(
              renderedEmail =
                Some(RenderResult("HELLO", "<H1>HELLO</H1>", "abc@test21.com", "My Subject", "sa", Some("templateId")))
            )
          )
        )

        when(mockRenderer.render(any[String], any[Map[String, String]], any[List[EmailAddress]])(any[HeaderCarrier]))
          .thenReturn(Future(Right((None, renderedResult))))

        private val pendingBeforeFailure = repository.collection
          .find()
          .toFuture()
          .futureValue
          .loneElement

        private val results = outbox.sendAll
        results.futureValue must be(EmailQueueProcessingResults(sent = 0, requeued = 1, 0, 0))

        private val pendingAfterFailure = repository.collection
          .find()
          .toFuture()
          .futureValue
          .loneElement

        pendingAfterFailure must have(Symbol("status")(Failed))
        pendingAfterFailure.updatedAt.isAfter(pendingBeforeFailure.updatedAt) must be(true)
      }
    }
  }

  "For imi" when {
    "after sending an email, the record in mongo" should {
      "be removed if the request is successfully processed" in new TestCase {
        private val outbox = new TestOutbox {
          new Queue(repository)
        }.get(true)

        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest()))

        when(mockImi.send(any[EmailContent])).thenReturn(Future.successful(Right(imiResponse)))

        eventually {
          outbox.sendAll.futureValue must be(
            EmailQueueProcessingResults(sent = 3, requeued = 0, permanentlyFailed = 0, aborted = 0)
          )
        }
        repository.collection.find().toFuture().futureValue must be(empty)
      }

      "be kept if the request processing fails" in new TestCase {

        private val outbox = new TestOutbox {}.get(true)

        await(repository.enqueue(generateAQueuedEmailRequest()))

        when(mockImi.send(any[EmailContent])).thenReturn(Future.successful(Left(ImiError("", "failed"))))
        private val pendingBeforeFailure = repository.collection.find().toFuture().futureValue.loneElement

        private val results = outbox.sendAll
        results.futureValue must be(EmailQueueProcessingResults(sent = 0, requeued = 1, 0, 0))

        private val pendingAfterFailure = repository.collection.find().toFuture().futureValue.loneElement

        pendingAfterFailure must have(Symbol("status")(Failed))
        pendingAfterFailure.updatedAt.isAfter(pendingBeforeFailure.updatedAt) must be(true)
      }

      "be kept if the request processing results with unexpected imi response" in new TestCase {

        private val mailgunClient = mock[HttpClientV2]
        private val mailgunConnectorStub = new MailgunConnector(senderDomainConfiguration, mailgunClient, "")
        private val outbox = new TestOutbox {}.get(true).copy(mailgunConnector = mailgunConnectorStub)

        await(
          repository.enqueue(
            generateAQueuedEmailRequest(
              renderedEmail =
                Some(RenderResult("HELLO", "<H1>HELLO</H1>", "abc@test21.com", "My Subject", "sa", Some("templateId")))
            )
          )
        )

        when(mockRenderer.render(any[String], any[Map[String, String]], any[List[EmailAddress]])(any[HeaderCarrier]))
          .thenReturn(Future(Right((None, renderedResult))))

        private val pendingBeforeFailure = repository.collection
          .find()
          .toFuture()
          .futureValue
          .loneElement

        private val results = outbox.sendAll
        results.futureValue must be(EmailQueueProcessingResults(sent = 0, requeued = 1, 0, 0))

        private val pendingAfterFailure = repository.collection
          .find()
          .toFuture()
          .futureValue
          .loneElement

        pendingAfterFailure must have(Symbol("status")(Failed))
        pendingAfterFailure.updatedAt.isAfter(pendingBeforeFailure.updatedAt) must be(true)
      }
    }
  }

  "Sending multiple emails" should {
    "store as a single queue item if its not imi" in new TestCase {
      private val outbox = new TestOutbox {}.get(isImiConnector = false)
      await(outbox.store(generateAQueuedEmailRequest(to = List(EmailAddress("a@a.com"), EmailAddress("b@a.com")))))
      repository.collection.find().toFuture().futureValue.size must be(1)
    }
    "store as a multiple queue items (1 for each emailAddress) if isImiConnector is true" in new TestCase {
      private val outbox = new TestOutbox {}.get(isImiConnector = true)
      await(outbox.store(generateAQueuedEmailRequest(to = List(EmailAddress("a@a.com"), EmailAddress("b@a.com")))))
      repository.collection.find().toFuture().futureValue.size must be(2)
    }
  }

  "For mailgun" when {
    "Sending all emails when no onSendUrl callback is defined" should {

      "not process any items when cancelled" in new TestCase {
        private val outbox = new TestOutbox {}.get()

        outbox.cancel()

        await(outbox.sendAll(HeaderCarrier()))

        verify(mockQueueRepo, Mockito.never()).pullPendingEmail(any[Instant])
      }

      "return a count of the number of emails that were sent" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)

        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest(templateId = "template2")))

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 2, requeued = 0, 0, 0)
      }

      "audit the templateId, id from mailGun, content parameters, email address and  additional details from the request" in new TestCase {

        private val outbox = new TestOutbox {}.get()
        private val messageId = UUID.randomUUID().toString
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.successful(MailgunSendResponse(id = MailgunId(messageId), message = "message")))

        await(
          outbox.store(
            generateAQueuedEmailRequest(
              to = List(EmailAddress("a@a.com"), EmailAddress("b@b.com")),
              templateId = "aTemplateId",
              parameters = Map("param1" -> "param_value1", "param2" -> "param_value2"),
              auditData = Map("nino" -> "SJ123456A", "key2" -> "value2")
            )
          )
        )

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        auditEvents.loneElement.detail must be(
          Map(
            "mailgunMessageId" -> messageId,
            "templateId"       -> "aTemplateId",
            "templateVariant"  -> "n/a",
            "nino"             -> "SJ123456A",
            "key2"             -> "value2",
            "content_param1"   -> "param_value1",
            "content_param2"   -> "param_value2",
            "senderDomain"     -> "aSenderDomain",
            "to"               -> """["a@a.com","b@b.com"]"""
          )
        )

      }

      "audit tags of the email request" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val messageId = UUID.randomUUID().toString
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.successful(MailgunSendResponse(id = MailgunId(messageId), message = "message")))

        await(
          outbox.store(
            generateAQueuedEmailRequest(
              to = List(EmailAddress("a@a.com"), EmailAddress("b@b.com")),
              templateId = "aTemplateId",
              parameters = Map("param1" -> "param_value1", "param2" -> "param_value2"),
              auditData = Map("nino" -> "SJ123456A", "key2" -> "value2"),
              tags = Map("messageId" -> "externalRefId", "enrolment" -> "HMRC-CUS-ORG", "source" -> "gmc")
            )
          )
        )

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        auditEvents.loneElement.tags mustBe
          Map(
            "transactionName" -> "Email Sent",
            "messageId"       -> "externalRefId",
            "enrolment"       -> "HMRC-CUS-ORG",
            "source"          -> "gmc"
          )
      }

      "send a request even when audit fails" in new TestCase {

        private val outbox = new TestOutbox {
          override val auditConnector: AuditConnector = fakeFailingAuditConnector
        }.get()
        private val messageId = UUID.randomUUID().toString
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.successful(MailgunSendResponse(id = MailgunId(messageId), message = "message")))

        await(
          outbox.store(
            generateAQueuedEmailRequest(
              templateId = "aTemplateId",
              auditData = Map("key1" -> "value1", "key2" -> "value2")
            )
          )
        )

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

      }

      "return zero if there are no emails to send" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 0, requeued = 0, 0, 0)
        verifyNoMoreInteractions(mockMailgun)
      }
    }
  }

  "For imi" when {
    "Sending all emails when no onSendUrl callback is defined" should {

      "not process any items when cancelled" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)

        outbox.cancel()

        await(outbox.sendAll(HeaderCarrier()))

        verify(mockQueueRepo, Mockito.never()).pullPendingEmail(any[Instant])
      }

      "return a count of the number of emails that were sent" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        when(mockImi.send(any[EmailContent])).thenReturn(imiSuccessResponse)

        await(outbox.store(generateAQueuedEmailRequest()))
        await(outbox.store(generateAQueuedEmailRequest(templateId = "template2")))

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 2, requeued = 0, 0, 0)
      }

      "audit the templateId, id from mailGun, content parameters, email address and  additional details from the request" in new TestCase {

        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        when(mockImi.send(any[EmailContent])).thenReturn(imiSuccessResponse)
        await(
          outbox.store(
            generateAQueuedEmailRequest(
              to = List(EmailAddress("a@a.com"), EmailAddress("b@b.com")),
              templateId = "aTemplateId",
              parameters = Map("param1" -> "param_value1", "param2" -> "param_value2"),
              auditData = Map("nino" -> "SJ123456A", "key2" -> "value2")
            )
          )
        )
        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 2, requeued = 0, 0, 0)
        auditEvents.size mustBe 2
      }

      "audit tags of the email request" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        when(mockImi.send(any[EmailContent]))
          .thenReturn(imiSuccessResponse)

        await(
          outbox.store(
            generateAQueuedEmailRequest(
              to = List(EmailAddress("a@a.com"), EmailAddress("b@b.com")),
              templateId = "aTemplateId",
              parameters = Map("param1" -> "param_value1", "param2" -> "param_value2"),
              auditData = Map("nino" -> "SJ123456A", "key2" -> "value2"),
              tags = Map("messageId" -> "externalRefId", "enrolment" -> "HMRC-CUS-ORG", "source" -> "gmc")
            )
          )
        )

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 2, requeued = 0, 0, 0)

        auditEvents.size mustBe 2
      }

      "send a request even when audit fails" in new TestCase {

        private val outbox = new TestOutbox {
          override val auditConnector: AuditConnector = fakeFailingAuditConnector
        }.get(isImiConnector = true)
        when(mockImi.send(any[EmailContent]))
          .thenReturn(imiSuccessResponse)

        await(
          outbox.store(
            generateAQueuedEmailRequest(
              templateId = "aTemplateId",
              auditData = Map("key1" -> "value1", "key2" -> "value2")
            )
          )
        )

        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

      }

      "return zero if there are no emails to send" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        outbox.sendAll.futureValue mustBe EmailQueueProcessingResults(sent = 0, requeued = 0, 0, 0)
        verifyNoMoreInteractions(mockImi)
      }
    }
  }

  "For mailgun" when {
    "The throttledSend function" should {
      "Successfully complete for sent unforced emails" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val sentMessage: ArgumentCaptor[EmailMessage] = ArgumentCaptor.forClass(classOf[EmailMessage])
        when(mockMailgun.send(sentMessage.capture())).thenReturn(success)

        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequest))

        sentMessage.getValue must be(expectedMessage)
        verify(mockMailgun, Mockito.never()).deleteBouncesFor(any[EmailAddress])
      }

      "Successfully complete for sent forced emails" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val emailAddressWhereBouncesDeleted: ArgumentCaptor[EmailAddress] =
          ArgumentCaptor.forClass(classOf[EmailAddress])
        when(mockMailgun.deleteBouncesFor(emailAddressWhereBouncesDeleted.capture()))
          .thenReturn(Future.successful(true))

        private val sentMessage: ArgumentCaptor[EmailMessage] = ArgumentCaptor.forClass(classOf[EmailMessage])
        when(mockMailgun.send(sentMessage.capture())).thenReturn(success)

        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce))

        emailAddressWhereBouncesDeleted.getAllValues.loneElement must be(emailRequest.item.to.loneElement)
        sentMessage.getValue must be(expectedMessage)
      }

      "Successfully complete for unforced emails that would have failed being deleted in mailgun" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.failed(new RuntimeException("Ooops")))
        private val sentMessage: ArgumentCaptor[EmailMessage] = ArgumentCaptor.forClass(classOf[EmailMessage])
        when(mockMailgun.send(sentMessage.capture())).thenReturn(success)

        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequest))
        sentMessage.getValue must be(expectedMessage)
      }

      "Requeue forced emails when the deleting from mailgun failed" in new TestCase {
        private val outbox = new TestOutbox {}.get()

        // given
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.failed(new RuntimeException("Ooops")))
        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)
        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        // when
        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        // then
        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Requeue emails when the sending to mailgun failed" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.successful(true))

        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.failed(new RuntimeException("Ooops")))

        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Requeue emails when the sending to mailgun when mailgun responses with 400 but the email address is valid" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.successful(true))
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.failed(new BadRequestException("")))
        when(mockMailgun.validate(NonEmptyString.validate(toAddress).toOption.get))
          .thenReturn(Future.successful(true))

        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Permanently fail the email and bounce the user if mailgun send responses with 400 status and email is not valid" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.successful(true))
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.failed(new BadRequestException("a bad thing happened, probably a bad email address")))
        when(mockMailgun.validate(NonEmptyString.validate(toAddress).toOption.get))
          .thenReturn(Future.successful(false))

        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 0, permanentlyFailed = 1, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .map(Option(_))
          .futureValue must be(None)

      }

      "Requeue email when the validation fails" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockMailgun.deleteBouncesFor(any[EmailAddress]))
          .thenReturn(Future.successful(true))
        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.failed(new BadRequestException("")))
        when(mockMailgun.validate(NonEmptyString.validate(toAddress).toOption.get))
          .thenReturn(Future.failed(new Exception("failed to validate")))

        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Requeue emails that are to recipient domains on the holdlist" in new TestCase {

        val outbox = new TestOutbox {

          override val sendToMailgun = new SendToMailgun(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(false, "", ""),
            emailRepository = repository,
            mailgunConnector = mailgunConnector,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List(RecipientDomainPattern.from("b.com")),
            allowList = allowList,
            throttler = throttler
          )

        }.get()

        await(repository.collection.insertOne(emailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        verifyNoInteractions(mockMailgun)
        repository.collection
          .find(Filters.equal("_id", emailRequest.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Send emails when no allowList included" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        await(repository.collection.insertOne(emailRequest).toFuture())
        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockMailgun).send(any[EmailMessage])
      }

      "Requeue emails that are to recipient domains not on the allowList" in new TestCase {
        private val updatedEmailRequest =
          emailRequest.copy(item = emailRequest.item.copy(to = List(EmailAddress("foo@gov.uk"))))

        val outbox = new TestOutbox {

          override val sendToMailgun = new SendToMailgun(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(false, "", ""),
            emailRepository = repository,
            mailgunConnector = mailgunConnector,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler
          )

        }.get()

        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(0, 0, 0, 0)

        verifyNoInteractions(mockMailgun)
        repository.collection
          .find(Filters.equal("_id", emailRequest.id))
          .first()
          .toFuture()
          .map(Option(_))
          .futureValue must be(None)
      }

      "Send emails that are to recipient domains on the allowList" in new TestCase {
        private val updatedEmailRequest =
          emailRequest.copy(item = emailRequest.item.copy(to = List(EmailAddress("foo@digital.hmrc.gov.uk"))))

        val outbox = new TestOutbox {

          override val sendToMailgun = new SendToMailgun(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(false, "", ""),
            emailRepository = repository,
            mailgunConnector = mailgunConnector,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler
          )

        }.get()

        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)
        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockMailgun).send(any[EmailMessage])
      }

      "Only send emails that are in the allowList" in new TestCase {
        private val updatedEmailRequest = emailRequest.copy(
          item = emailRequest.item.copy(to = List(EmailAddress("foo@digital.hmrc.gov.uk"), EmailAddress("foo@gov.uk")))
        )
        val outbox = new TestOutbox {
          override val sendToMailgun = new SendToMailgun(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(false, "", ""),
            emailRepository = repository,
            mailgunConnector = mailgunConnector,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler
          )

        }.get()

        private val captor: ArgumentCaptor[EmailMessage] = ArgumentCaptor.forClass(classOf[EmailMessage])
        when(mockMailgun.send(any[EmailMessage])).thenReturn(success)
        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockMailgun).send(captor.capture)
        captor.getValue.to mustBe List(EmailAddress("foo@digital.hmrc.gov.uk"))
      }
    }
  }

  "For imi" when {
    "The throttledSend function if imi" should {
      "Successfully complete for sent unforced emails" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)

        private val sentMessage: ArgumentCaptor[EmailContent] = ArgumentCaptor.forClass(classOf[EmailContent])
        when(mockImi.send(sentMessage.capture())).thenReturn(Future.successful(Right(imiResponse)))
        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequest, correlationId))
        sentMessage.getValue must be(expectedEmailContent)
      }

      "Successfully complete for sent forced emails with record in contact policy" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)

        private val sentMessage: ArgumentCaptor[EmailContent] = ArgumentCaptor.forClass(classOf[EmailContent])
        when(mockImi.send(sentMessage.capture())).thenReturn(Future.successful(Right(imiResponse)))
        when(mockImi.getConsent(any[EmailAddress], any[String]))
          .thenReturn(Future.successful(Some(ConsentItem(Channel.EMAIL, "a@b.com", true, "reason", Instant.now()))))

        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce, correlationId))

        sentMessage.getValue must be(expectedEmailContent)
      }

      "Successfully complete for sent forced emails with no record in contact policy" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)

        private val sentMessage: ArgumentCaptor[EmailContent] = ArgumentCaptor.forClass(classOf[EmailContent])
        when(mockImi.send(sentMessage.capture())).thenReturn(Future.successful(Right(imiResponse)))
        when(mockImi.getConsent(any[EmailAddress], any[String])).thenReturn(Future.successful(None))

        await(outbox.throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce, correlationId))

        sentMessage.getValue must be(expectedEmailContent)
      }

      "Requeue emails when the sending to imi failed" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)

        when(mockImi.getConsent(any[EmailAddress], any[String]))
          .thenReturn(Future.successful(None))
        when(mockImi.send(any[EmailContent]))
          .thenReturn(Future.failed(new RuntimeException("Ooops")))

        await(repository.collection.insertOne(emailRequestWithForce).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Requeue emails that are to recipient domains on the holdlist" in new TestCase {

        val outbox = new TestOutbox {

          override val sendToImi = new SendToImi(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(true, "", ""),
            emailRepository = repository,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List(RecipientDomainPattern.from("b.com")),
            allowList = allowList,
            throttler = throttler,
            imiConnector = mockImi,
            domainName = "tax.gov.uk",
            emailEventsRepository = emailEventsRepo,
            mockEmailStatsRepository
          )

        }.get(isImiConnector = true)

        await(repository.collection.insertOne(emailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        verifyNoInteractions(mockImi)
        repository.collection
          .find(Filters.equal("_id", emailRequest.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }

      "Send emails when no allowList included" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        await(repository.collection.insertOne(emailRequest).toFuture())
        when(mockImi.send(any[EmailContent])).thenReturn(imiSuccessResponse)

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockImi).send(any[EmailContent])
      }

      "Requeue emails that are to recipient domains not on the allowList" in new TestCase {
        private val updatedEmailRequest =
          emailRequest.copy(item = emailRequest.item.copy(to = List(EmailAddress("foo@gov.uk"))))

        val outbox = new TestOutbox {

          override val sendToImi = new SendToImi(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(true, "", ""),
            emailRepository = repository,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler,
            emailEventsRepository = emailEventsRepo,
            domainName = "tax.gov.uk",
            imiConnector = mockImi,
            mockEmailStatsRepository
          )

        }.get(isImiConnector = true)

        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(0, 0, 0, 0)
        verifyNoInteractions(mockImi)
        repository.collection
          .find(Filters.equal("_id", emailRequest.id))
          .first()
          .toFuture()
          .map(Option(_))
          .futureValue must be(None)
      }

      "Send emails that are to recipient domains on the allowList" in new TestCase {
        private val updatedEmailRequest =
          emailRequest.copy(item = emailRequest.item.copy(to = List(EmailAddress("foo@digital.hmrc.gov.uk"))))

        val outbox = new TestOutbox {

          override val sendToImi = new SendToImi(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(true, "", ""),
            emailRepository = repository,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler,
            emailEventsRepository = emailEventsRepo,
            domainName = "tax.gov.uk",
            imiConnector = mockImi,
            mockEmailStatsRepository
          )

        }.get(isImiConnector = true)

        when(mockImi.send(any[EmailContent])).thenReturn(imiSuccessResponse)
        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockImi).send(any[EmailContent])
      }

      "Only send emails that are in the allowList" in new TestCase {
        private val updatedEmailRequest = emailRequest.copy(
          item = emailRequest.item.copy(to = List(EmailAddress("foo@digital.hmrc.gov.uk"), EmailAddress("foo@gov.uk")))
        )
        val outbox = new TestOutbox {
          override val sendToImi = new SendToImi(
            senderDomain = senderDomain,
            encryption = encryption,
            imiConfiguration = new ImiConfiguration(useImiConnector = true, "", ""),
            emailRepository = repository,
            auditConnector = auditConnector,
            preSendingCheck = preSendingCheck,
            holdList = List.empty,
            allowList = List(RecipientDomainPattern.from("digital.hmrc.gov.uk")),
            throttler = throttler,
            domainName = "gov.uk",
            emailEventsRepository = emailEventsRepo,
            imiConnector = mockImi,
            mockEmailStatsRepository
          )

        }.get(isImiConnector = true)

        private val captor: ArgumentCaptor[EmailContent] = ArgumentCaptor.forClass(classOf[EmailContent])
        when(mockImi.send(any[EmailContent])).thenReturn(imiSuccessResponse)
        await(repository.collection.insertOne(updatedEmailRequest).toFuture())

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, updatedEmailRequest)
          .futureValue mustBe EmailQueueProcessingResults(sent = 1, requeued = 0, 0, 0)

        verify(mockImi).send(captor.capture)
        captor.getValue.to
          .flatMap(_.email) mustBe List(EmailAddress("foo@digital.hmrc.gov.uk"), EmailAddress("foo@gov.uk"))
      }
    }
  }

  "For mailgun" when {
    "For legacy email requests, outbox" should {
      "Not attempt to recover from template rendering failures" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        when(mockRenderer.render(any[String], any[Params], any[List[EmailAddress]])(any[HeaderCarrier]))
          .thenReturn(Future(Left(ErrorMessage(""))))

        when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))

        val x: InsertOneResult = repository.collection.insertOne(emailRequestWithForce).toFuture().futureValue
        x.wasAcknowledged() must be(true)

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }
    }
  }

  "For imi" when {
    "For legacy email requests, outbox" should {
      "Not attempt to recover from template rendering failures" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        when(mockRenderer.render(any[String], any[Params], any[List[EmailAddress]])(any[HeaderCarrier]))
          .thenReturn(Future(Left(ErrorMessage(""))))
        when(mockImi.getConsent(any[EmailAddress](), any[String]())).thenReturn(Future.successful(None))
        val x: InsertOneResult = repository.collection.insertOne(emailRequestWithForce).toFuture().futureValue
        x.wasAcknowledged() must be(true)

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
          .futureValue mustBe EmailQueueProcessingResults(0, requeued = 1, 0, 0)

        repository.collection
          .find(Filters.equal("_id", emailRequestWithForce.id))
          .first()
          .toFuture()
          .futureValue
          .status must be(Failed)
      }
    }
  }

  "For mailgun" when {
    "Pre sending email check" should {
      "remove the email from the queue without sending if callback returns with 'do not send'" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))

        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Right(SendAlertResponse(false))))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(aborted = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .map(Option(_))
          .futureValue mustBe None
      }

      "continue as normal if callback returns with 'ok to send'" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockMailgun.send(any[EmailMessage]))
          .thenReturn(Future.successful(MailgunSendResponse(id = MailgunId("messageId"), message = "message")))
        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Right(SendAlertResponse(true))))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(sent = 1)
      }

      "fail the email sending and try again later if the callback fails to complete" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.failed(new RuntimeException("")))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(requeued = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .futureValue
          .status mustBe Failed
      }

      "permanently fail the email sending if the callback url is invalid" in new TestCase {
        private val outbox = new TestOutbox {}.get()
        private val expectedCallbackUrl = "http://invalidHost/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Left(true)))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(permanentlyFailed = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .futureValue
          .status mustBe PermanentlyFailed
      }
    }
  }

  "For Imi" when {
    "For Imi Pre sending email check" should {
      "remove the email from the queue without sending if callback returns with 'do not send'" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))

        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Right(SendAlertResponse(false))))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(aborted = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .map(Option(_))
          .futureValue mustBe None
      }

      "continue as normal if callback returns with 'ok to send'" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockImi.send(any[EmailContent])).thenReturn(Future.successful(Right(imiResponse)))
        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Right(SendAlertResponse(true))))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(sent = 1)
      }

      "fail the email sending and try again later if the callback fails to complete" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        private val expectedCallbackUrl = "http://validHost:port/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.failed(new RuntimeException("")))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(requeued = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .futureValue
          .status mustBe Failed
      }

      "permanently fail the email sending if the callback url is invalid" in new TestCase {
        private val outbox = new TestOutbox {}.get(isImiConnector = true)
        private val expectedCallbackUrl = "http://invalidHost/test/callback"
        private val sendEmailRequestWI =
          buildWorkItem(generateAQueuedEmailRequest(onSendUrl = Some(expectedCallbackUrl)))
        await(repository.collection.insertOne(sendEmailRequestWI).toFuture())

        when(mockPreSendingCheck.shouldISend(expectedCallbackUrl))
          .thenReturn(Future.successful(Left(true)))

        outbox
          .throttledSend(EmailQueueProcessingResults.empty, sendEmailRequestWI)
          .futureValue mustBe EmailQueueProcessingResults.empty.copy(permanentlyFailed = 1)

        repository.collection
          .find(Filters.equal("_id", sendEmailRequestWI.id))
          .first()
          .toFuture()
          .futureValue
          .status mustBe PermanentlyFailed
      }
    }
  }

  "DomainPattern" should {
    "not allow an invalid regex in the holdlist" in {
      intercept[ConfigException.BadValue] {
        RecipientDomainPattern.from("[")
      }
    }
  }

  "not call deleteBouncesFor in mailgun but call deleteConsent in imi, when imiConnector is true and force flag true" in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = true)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    when(mockImi.getConsent(any[EmailAddress], any[String]))
      .thenReturn(
        Future.successful(
          Some(
            ConsentItem(
              "email",
              "test@gmail.com",
              consent = false,
              "Email force requested",
              Instant.parse("2023-05-24T16:04:36.548Z")
            )
          )
        )
      )
    when(mockImi.deleteConsent(any[EmailAddress], any[String], any[Boolean]))
      .thenReturn(Future.successful(200))

    override val emailRequestWithForce: WorkItem[QueuedEmailRequest] = buildWorkItem(
      generateAQueuedEmailRequest(
        force = true,
        templateId = "transactionEngineHMRCSASA100Success",
        to = List(EmailAddress("test@gmail.com"))
      )
    )
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
    )
    verify(mockImi, times(1)).send(any[EmailContent])
    verify(mockMailgun, times(0)).deleteBouncesFor(any[EmailAddress])
    verify(mockImi, times(1)).deleteConsent(any[EmailAddress], any[String], any[Boolean])
  }

  "call deleteConsent in imi, only when ConsentItem, consent uuid is false" in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = true)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    when(mockImi.getConsent(any[EmailAddress], any[String]))
      .thenReturn(
        Future.successful(
          Some(
            ConsentItem(
              "email",
              "test@gmail.com",
              consent = false,
              "Email force requested",
              Instant.parse("2023-05-24T16:04:36.548Z")
            )
          )
        )
      )
    when(mockImi.deleteConsent(any[EmailAddress], any[String], any[Boolean]))
      .thenReturn(Future.successful(200))

    override val emailRequestWithForce: WorkItem[QueuedEmailRequest] = buildWorkItem(
      generateAQueuedEmailRequest(
        force = true,
        templateId = "transactionEngineHMRCSASA100Success",
        to = List(EmailAddress("test@gmail.com"))
      )
    )
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
    )
    verify(mockImi, times(1)).send(any[EmailContent])
    verify(mockMailgun, times(0)).deleteBouncesFor(any[EmailAddress])
    verify(mockImi, times(1)).getConsent(any[EmailAddress], any[String])
    verify(mockImi, times(1)).deleteConsent(any[EmailAddress], any[String], any[Boolean])
  }

  "do not call addConsent with imi, when ConsentItem is empty" in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = true)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    when(mockImi.getConsent(any[EmailAddress], any[String]))
      .thenReturn(Future.successful(None))
    when(mockImi.addConsent(any[String], any[String], any[Boolean], any[String]))
      .thenReturn(Future.successful(true))

    override val emailRequestWithForce: WorkItem[QueuedEmailRequest] = buildWorkItem(
      generateAQueuedEmailRequest(
        force = true,
        templateId = "transactionEngineHMRCSASA100Success",
        to = List(EmailAddress("test@gmail.com"))
      )
    )
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
    )
    verify(mockImi, times(1)).send(any[EmailContent])
    verify(mockMailgun, times(0)).deleteBouncesFor(any[EmailAddress])
    verify(mockImi, times(1)).getConsent(any[EmailAddress], any[String])
    verify(mockImi, times(0)).addConsent(any[String], any[String], any[Boolean], any[String])
  }

  "do not call addConsent with imi, when ConsentItem, addConsent uuid is true" in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = true)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    when(mockImi.getConsent(any[EmailAddress], any[String]))
      .thenReturn(
        Future.successful(
          Some(
            ConsentItem(
              "email",
              "test@gmail.com",
              consent = true,
              "Email force requested",
              Instant.parse("2023-05-24T16:04:36.548Z")
            )
          )
        )
      )
    when(mockImi.addConsent(any[String], any[String], any[Boolean], any[String]))
      .thenReturn(Future.successful(true))

    override val emailRequestWithForce: WorkItem[QueuedEmailRequest] = buildWorkItem(
      generateAQueuedEmailRequest(
        force = true,
        templateId = "transactionEngineHMRCSASA100Success",
        to = List(EmailAddress("test@gmail.com"))
      )
    )
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
    )
    verify(mockImi, times(1)).send(any[EmailContent])
    verify(mockMailgun, times(0)).deleteBouncesFor(any[EmailAddress])
    verify(mockImi, times(1)).getConsent(any[EmailAddress], any[String])
    verify(mockImi, times(0)).addConsent(any[String], any[String], any[Boolean], any[String])
  }

  "call deleteBouncesFor in mailgun as normal when isImiConnector is false and force flag true" in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = false)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequestWithForce)
    )
    verify(mockMailgun, times(1)).deleteBouncesFor(any[EmailAddress])
  }

  "not call deleteBouncesFor in mailgun when force flag is false and isImiConnector is false " in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = false)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
    )
    verify(mockMailgun, times(0)).deleteBouncesFor(any[EmailAddress])
  }

  "not call addConsent in imi when force flag is false and isImiConnector is true " in new TestCase {
    private val outbox = new TestOutbox {}.get(isImiConnector = true)
    when(mockMailgun.deleteBouncesFor(any[EmailAddress])).thenReturn(Future.successful(true))
    await(
      outbox
        .throttledSend(EmailQueueProcessingResults.empty, emailRequest)
    )
    verify(mockImi, times(0)).addConsent(any[String], any[String], any[Boolean], any[String])
  }

  trait TestCase extends QueuedEmailRequestGenerator with FakeSenderDomainConfiguration {

    val emailRequest: WorkItem[QueuedEmailRequest] = buildWorkItem(generateAQueuedEmailRequest())
    val emailRequestWithForce: WorkItem[QueuedEmailRequest] = buildWorkItem(generateAQueuedEmailRequest(force = true))
    val success: Future[MailgunSendResponse] =
      Future.successful(MailgunSendResponse(id = MailgunId("id"), message = "message"))

    val imiSuccessResponse: Future[Right[Nothing, ImiSendResponse]] = Future.successful(
      Right(
        ImiSendResponse(
          "2023-01-06T15:30:11.676Z",
          "c06bcfad-21ca-4ebd-a805-e220419e9a35",
          "ded81d59-9e8a-4806-b5d1-a82ea350b056",
          "queued"
        )
      )
    )
    val imiResponse = ImiSendResponse(
      "2023-01-06T15:30:11.676Z",
      "c06bcfad-21ca-4ebd-a805-e220419e9a35",
      "ded81d59-9e8a-4806-b5d1-a82ea350b056",
      "queued"
    )
    implicit val emptyHeaderCarrier: HeaderCarrier = HeaderCarrier()

    val mockLockKeeper = TestLockKeepers.lockKeeperWhichGetsTheLock

    val mockMailgun: MailgunConnector = mock[MailgunConnector]
    val mockImi: ImiConnector = mock[ImiConnector]
    val mockEmailEventsRepository: EmailEventsRepository = mock[EmailEventsRepository]
    val mockEmailStatsRepository: EmailStatsRepository = mock[EmailStatsRepository]
    val workItem = buildEmailEventsItemWorkItem(EmailEventsItem("", None, Map.empty, None, ""))

    when(
      mockEmailEventsRepository.markSent(
        any[String](),
        any[Option[String]](),
        any[Option[String]](),
        any[String](),
        any[Instant](),
        any[Instant]()
      )
    ).thenReturn(Future.successful(workItem))

    val mockPreSendingCheck: PreSendingCheckConnector =
      mock[PreSendingCheckConnector]
    val mockRenderer: EmailRendererConnector = mock[EmailRendererConnector]
    val mockQueueRepo: EmailQueueRepository = mock[EmailQueueRepository]
    val encryption = mock[Encryption]
    when(encryption.encrypt(any[String]()))
      .thenReturn(Crypted("encryptedString"))

    val fakeThrottler: WorkThrottling = new WorkThrottling {
      override def throttledStartingFrom[T](start: Instant)(f: Future[T])(implicit ec: ExecutionContext): Future[T] = f
    }

    var auditEvents: List[DataEvent] = List.empty

    extension (auditConnector: AuditConnector) def auditEvents: List[DataEvent] = List.empty

    val fakeAuditConnector: AuditConnector = new AuditConnector {
      override def auditingConfig: AuditingConfig = ???

      override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
        Future
          .successful(AuditResult.Success)
          .andThen { case _ =>
            auditEvents = event.asInstanceOf[DataEvent] :: auditEvents
          }(ec)

      override def auditChannel: AuditChannel = ???

      override def datastreamMetrics: DatastreamMetrics = ???
    }

    val fakeFailingAuditConnector = new AuditConnector {

      override def auditingConfig: AuditingConfig = ???

      override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
        Future.failed(new Exception("fakeFailingAuditConnector"))

      override def auditChannel: AuditChannel = ???

      override def datastreamMetrics: DatastreamMetrics = ???
    }

    def buildWorkItem(sendEmailRequest: QueuedEmailRequest): WorkItem[QueuedEmailRequest] =
      WorkItem(
        id = new ObjectId(), // .parse("5448d77f01000001000b84e7").get,
        receivedAt = Instant.ofEpochMilli(1411139649671L), // new DateTime(, ISOChronology.getInstanceUTC),
        updatedAt = Instant.ofEpochMilli(1411139649671L),
        status = InProgress,
        failureCount = 0,
        item = sendEmailRequest,
        availableAt = Instant.ofEpochMilli(1411139649671L)
      )

    def buildEmailEventsItemWorkItem(emailEventsItem: EmailEventsItem) =
      WorkItem(
        id = new ObjectId(),
        receivedAt = Instant.ofEpochMilli(1411139649671L), // new DateTime(, ISOChronology.getInstanceUTC),
        updatedAt = Instant.ofEpochMilli(1411139649671L),
        status = InProgress,
        failureCount = 0,
        item = emailEventsItem,
        availableAt = Instant.ofEpochMilli(1411139649671L)
      )

    trait TestOutbox {
      val senderDomain: String = "aSenderDomain"
      val queueName: String = ""
      val rendererConnector: EmailRendererConnector = mockRenderer
      val queueRepo: EmailQueueRepository = repository
      val emailEventsRepo: EmailEventsRepository = mockEmailEventsRepository
      val mailgunConnector: MailgunConnector = mockMailgun
      val imiConnector: ImiConnector = mockImi
      val auditConnector: AuditConnector = fakeAuditConnector
      val preSendingCheck: PreSendingCheckConnector = mockPreSendingCheck
      val holdList: List[RecipientDomainPattern] = List.empty
      val allowList: List[RecipientDomainPattern] = List.empty
      val throttler: WorkThrottling = fakeThrottler
      val queue = new Queue(repository)
      val sendToImi = new SendToImi(
        senderDomain = senderDomain,
        domainName = "hmrc",
        encryption = encryption,
        imiConfiguration = new ImiConfiguration(true, "", ""),
        emailRepository = repository,
        emailEventsRepository = emailEventsRepo,
        imiConnector = imiConnector,
        auditConnector = auditConnector,
        preSendingCheck = preSendingCheck,
        holdList = holdList,
        allowList = allowList,
        throttler = throttler,
        mockEmailStatsRepository
      )
      val sendToMailgun = new SendToMailgun(
        senderDomain = senderDomain,
        encryption = encryption,
        imiConfiguration = new ImiConfiguration(false, "", ""),
        emailRepository = repository,
        mailgunConnector = mailgunConnector,
        auditConnector = auditConnector,
        preSendingCheck = preSendingCheck,
        holdList = holdList,
        allowList = allowList,
        throttler = throttler
      )

      def get(isImiConnector: Boolean = false, domain: String = "domainName"): Outbox =
        Outbox(
          senderDomain,
          domain,
          queueName,
          encryption,
          ImiConfiguration(isImiConnector, "", ""),
          rendererConnector,
          queueRepo,
          emailEventsRepo,
          mailgunConnector,
          imiConnector,
          auditConnector,
          preSendingCheck,
          holdList,
          allowList,
          throttler,
          queue,
          sendToImi,
          sendToMailgun
        )

    }

    def expectedMessage =
      EmailMessage(
        from = renderedResult.fromAddress,
        to = emailRequest.item.to,
        replyToAddress = emailRequest.item.replyToAddress,
        subject = renderedResult.subject,
        plainTextBody = renderedResult.plain,
        htmlBody = renderedResult.html,
        templateId = "template",
        templateRegime = "generic"
      )

    val correlationId = CorrelationId.apply()

    def expectedEmailContent =
      EmailContent(
        Channel.EMAIL,
        "noreply@hmrc",
        List(To(List(EmailAddress("a@b.com")), correlationId.value.toString)),
        "eyJyZWdpbWUiOiJlbmNyeXB0ZWRTdHJpbmciLCJ0ZW1wbGF0ZUlkIjoiZW5jcnlwdGVkU3RyaW5nIiwicGxhdGZvcm0iOiJlbmNyeXB0ZWRTdHJpbmciLCJDb250YWN0UG9saWN5R3JvdXBJZCI6IiJ9",
        Options(false, true, "from@me.com"),
        ContactPolicy("", true, true),
        List("submitted", "delivered", "not verified", "invalid", "bounce", "complaint", "read", "failed"),
        Content("html", "OH HAI!", None, "plaintext", "somehtml"),
        ""
      )

  }

}

object TestLockKeepers {

  trait FakeLockKeeper extends TimePeriodLockService {
    val ttl = 10.seconds

    val lockId: String = "test-lockId"

    val lockRepository: LockRepository = mock[LockRepository]
  }

  val lockKeeperWhichGetsTheLock: FakeLockKeeper = new FakeLockKeeper {
    override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(Some(_))(ec)

    override def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(Option(_))(ec)
  }

  val lockKeeperWhichDoesNotGetTheLock: FakeLockKeeper = new FakeLockKeeper {
    override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      Future.successful(None)

    override def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      Future.successful(None)
  }

  val lockKeeperWhichDoesNotRenewTheLock: FakeLockKeeper = new FakeLockKeeper {
    override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      Future.failed(new IllegalStateException())

    override def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      Future.failed(new IllegalStateException())
  }

}

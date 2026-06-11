/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mongodb.scala.bson.ObjectId
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ Eventually, IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.clusterworkthrottling.WorkThrottling
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ EMPTY_STRING, TEST_DOMAIN, TEST_MESSAGE_ID, TEST_URL }
import uk.gov.hmrc.email.connectors.{ ImiConnector, PreSendingCheckConnector }
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.model.EventMarkingStatus.Marked
import uk.gov.hmrc.email.repositories.model.{ EmailEventsItem, QueuedEmailRequest }
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EmailQueueRepository, EmailStatsRepository }
import uk.gov.hmrc.email.util.QueuedEmailRequestGenerator
import uk.gov.hmrc.email.utils.{ CorrelationId, Encryption }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.InProgress
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent

import java.time.{ Duration, Instant }
import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class SendToImiSpec
    extends SpecBase with ScalaFutures with IntegrationPatience with LoneElement with Eventually
    with DefaultPlayMongoRepositorySupport[WorkItem[QueuedEmailRequest]] with QueuedEmailRequestGenerator {

  override protected def checkTtlIndex: Boolean = false

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    val _ = repository.ensureIndexes().futureValue
  }

  val configuration: Configuration = mock[Configuration]
  val repository: EmailQueueRepository = new EmailQueueRepository("emailQueue", configuration, mongoComponent) {
    override lazy val inProgressRetryAfter: Duration = Duration.ofHours(1)

    override lazy val retryInterval: Long = Duration.ofMillis(10000).toMillis
  }

  "imi emailContent function" should {
    "correct fromAddress Subject for tax.service.gov.uk" in new TestCase {
      val userTags: Map[String, String] = Map("anyKey1" -> "anyValue", "anyKey2" -> "anyValue")

      val sendToImi: SendToImi = new SendToImiSetup {}.get("tax.service.gov.uk")

      val result: EmailContent = sendToImi.emailContent(
        generateAQueuedEmailRequest(templateId = "t1", tags = userTags),
        RenderResult(
          "plainText",
          "somehtml",
          "HMRC Check your Income Tax service <noreply@tax.service.gov.uk>",
          "subject",
          "sa",
          Some("templateId")
        ),
        correlationId,
        "XXX",
        "http://url"
      )
      result.options.fromName mustBe "HMRC Check your Income Tax service"
    }

    "correct fromAddress Subject for confirmation.tax.service.gov.uk" in new TestCase {
      val sendToImi = new SendToImiSetup {}.get("confirmation.tax.service.gov.uk")

      val userTags = Map("anyKey1" -> "anyValue", "anyKey2" -> "anyValue")
      val result = sendToImi.emailContent(
        generateAQueuedEmailRequest(templateId = "t1", tags = userTags),
        RenderResult(
          "plainText",
          "somehtml",
          "Gateway Confirmation <noreply@confirmation.tax.service.gov.uk>",
          "subject",
          "sa",
          Some("templateId")
        ),
        correlationId.toString,
        "XXX",
        "http://url"
      )
      result.options.fromName mustBe "Gateway Confirmation"
    }

    "correct fromAddress Subject for developer.tax.service.gov.uk" in new TestCase {
      val sendToImi = new SendToImiSetup {}.get("developer.tax.service.gov.uk")
      val userTags = Map("anyKey1" -> "anyValue", "anyKey2" -> "anyValue")
      val result = sendToImi.emailContent(
        generateAQueuedEmailRequest(templateId = "t1", tags = userTags),
        RenderResult(
          "plainText",
          "somehtml",
          "Software Developer Support Team <noreply@tax.service.gov.uk>",
          "subject",
          "sa",
          Some("templateId")
        ),
        correlationId,
        "XXX",
        "http://url"
      )
      result.options.fromName mustBe "Software Developer Support Team"
    }

    "have request tags and mandatory tags and ContactPolicyGroupId" in new TestCase {
      val sendToImi = new SendToImiSetup {}.get("domainName")
      val userTags = Map("anyKey1" -> "anyValue", "anyKey2" -> "anyValue")
      val mandatoryTags =
        Map("regime" -> "encryptedString", "templateId" -> "encryptedString", "platform" -> "encryptedString")
      val groupIdTag = Map("ContactPolicyGroupId" -> EMPTY_STRING)

      val result = sendToImi.emailContent(
        generateAQueuedEmailRequest(templateId = "t1", tags = userTags),
        renderedResult,
        correlationId.toString,
        "XXX",
        "http://url"
      )

      val encoded =
        Base64.getEncoder.encodeToString(Json.toJson(userTags ++ mandatoryTags ++ groupIdTag).toString.getBytes)

      result mustBe (
        EmailContent(
          Channel.EMAIL,
          "noreply@domainName",
          List(To(List(EmailAddress("a@b.com")), correlationId.toString)),
          encoded,
          Options(false, true, "from@me.com"),
          ContactPolicy("XXX", true, true),
          Seq("submitted", "delivered", "not verified", "invalid", "bounce", "complaint", "read", "failed"),
          Content("html", "OH HAI!", None, "plaintext", "somehtml"),
          "http://url"
        )
      )
    }
  }

  "markSent" should {
    "mark the message sent if number if attempt is less than 4" in new TestCase {}

    "throw exception if the attempts exceeds 4" in new TestCase {

      val sendToImi = new SendToImi(
        senderDomain = "aSenderDomain",
        domainName = TEST_DOMAIN,
        encryption = encryptionMock,
        imiConfiguration = ImiConfiguration(true, EMPTY_STRING, EMPTY_STRING),
        emailRepository = repository,
        emailEventsRepository = emailEventsRepository,
        imiConnector = mockImi,
        auditConnector = fakeAuditConnector,
        preSendingCheck = mockPreSendingCheck,
        holdList = List.empty,
        allowList = List.empty,
        throttler = fakeThrottler,
        mockEmailStatsRepository
      )

      intercept[RuntimeException] {
        sendToImi.markSent(
          messageId = TEST_MESSAGE_ID,
          eventUrl = Some(TEST_URL),
          emailSource = Some(""),
          senderDomain = TEST_DOMAIN,
          times = 5
        )
      }.getMessage must be("MarkSentFailed: For 1fghj234578999#uytre max attempts reached with count 5")
    }

  }

  class TestCase {
    val correlationId: String = CorrelationId.apply().value.toString
    val encryptionMock: Encryption = mock[Encryption]
    when(encryptionMock.encrypt(any[String]()))
      .thenReturn(Crypted("encryptedString"))

    val mockImi: ImiConnector = mock[ImiConnector]
    val emailEventsRepository: EmailEventsRepository = mock[EmailEventsRepository]
    when(emailEventsRepository.markEvent(any[String], any[EventType], any[Instant]))
      .thenReturn(Future.successful(Marked))
    val mockEmailStatsRepository: EmailStatsRepository = mock[EmailStatsRepository]

    val fakeAuditConnector: AuditConnector = new AuditConnector {
      var auditEvents: List[DataEvent] = List.empty

      override def auditingConfig: AuditingConfig = ???

      override def sendEvent(event: DataEvent)(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[AuditResult] =
        Future
          .successful(AuditResult.Success)
          .andThen { case _ =>
            auditEvents = event :: auditEvents
          }(ec)

      override def auditChannel: AuditChannel = ???

      override def datastreamMetrics: DatastreamMetrics = ???
    }

    val mockPreSendingCheck: PreSendingCheckConnector = mock[PreSendingCheckConnector]

    val fakeThrottler: WorkThrottling = new WorkThrottling {
      override def throttledStartingFrom[T](start: Instant)(f: Future[T])(implicit ec: ExecutionContext): Future[T] = f
    }

    def buildWorkItem(sendEmailRequest: QueuedEmailRequest): WorkItem[QueuedEmailRequest] =
      WorkItem(
        id = new ObjectId(),
        receivedAt = Instant.ofEpochMilli(1411139649671L), // new DateTime(, ISOChronology.getInstanceUTC),
        updatedAt = Instant.ofEpochMilli(1411139649671L),
        status = InProgress,
        failureCount = 0,
        item = sendEmailRequest,
        availableAt = Instant.ofEpochMilli(1411139649671L)
      )

    trait SendToImiSetup {
      val senderDomain = "aSenderDomain"

      val domainName = "hmrc"

      val encryption: Encryption = encryptionMock

      val imiConfiguration: ImiConfiguration = ImiConfiguration(true, EMPTY_STRING, EMPTY_STRING)

      val emailRepository: EmailQueueRepository = repository

      val imiConnector: ImiConnector = mockImi

      val auditConnector: AuditConnector = fakeAuditConnector
      val preSendingCheck: PreSendingCheckConnector = mockPreSendingCheck
      val holdList: List[Nothing] = List.empty

      val allowList: List[Nothing] = List.empty

      val throttler: WorkThrottling = fakeThrottler

      def get(domainName: String) =
        new SendToImi(
          senderDomain = "aSenderDomain",
          domainName = domainName,
          encryption = encryptionMock,
          imiConfiguration = ImiConfiguration(true, EMPTY_STRING, EMPTY_STRING),
          emailRepository = repository,
          emailEventsRepository = emailEventsRepository,
          imiConnector = mockImi,
          auditConnector = fakeAuditConnector,
          preSendingCheck = mockPreSendingCheck,
          holdList = List.empty,
          allowList = List.empty,
          throttler = fakeThrottler,
          mockEmailStatsRepository
        )
    }
  }
}

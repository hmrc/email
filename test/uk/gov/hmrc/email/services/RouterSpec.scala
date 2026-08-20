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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.test.Helpers.*
import uk.gov.hmrc.clusterworkthrottling.{ Rate, WorkThrottling }
import uk.gov.hmrc.crypto.Crypted
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.EMPTY_STRING
import uk.gov.hmrc.email.connectors.*
import uk.gov.hmrc.email.controllers.model.SendEmailRequest
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.{ ImiConfiguration, RenderResult }
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EmailQueueRepository }
import uk.gov.hmrc.email.services.Priority.{ Priority, standard }
import uk.gov.hmrc.email.utils.Encryption
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import org.mockito.ArgumentMatchers.any

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class RouterSpec extends SpecBase {

  val actorSystem: ActorSystem = ActorSystem("RouterSpec")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)
  implicit val hc: HeaderCarrier = HeaderCarrier()

  "router" should {

    "store emails in the specified alertQueue outbox" in new TestCase {
      emailRendererRespondsWithPriority(Some(Priority.standard))

      await(router.store(sampleRequestWithAlertQueue))

      fakeDefaultdOutbox.stored mustBe None
      fakeBackgroundOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe Some(
        QueuedEmailRequest.from(sampleRequestWithAlertQueue, renderResult, mockEncryption)
      )
    }

    "store urgent emails in the priority outbox" in new TestCase {
      emailRendererRespondsWithPriority(Some(Priority.urgent))

      await(router.store(sampleRequest))

      fakeDefaultdOutbox.stored mustBe None
      fakeBackgroundOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe Some(QueuedEmailRequest.from(sampleRequest, renderResult, mockEncryption))
    }

    "store non-urgent emails in the default outbox" in new TestCase {
      emailRendererRespondsWithPriority(Some(Priority.standard))

      val sampleRequestWithDefaultAlertQueue: SendEmailRequest = sampleRequest.copy(alertQueue = Some("default"))

      await(router.store(sampleRequestWithDefaultAlertQueue))

      fakeBackgroundOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe None
      fakeDefaultdOutbox.stored mustBe Some(
        QueuedEmailRequest.from(sampleRequestWithDefaultAlertQueue, renderResult, mockEncryption)
      )
    }

    "store low-priority emails in the background outbox" in new TestCase {
      emailRendererRespondsWithPriority(Some(Priority.background))

      await(router.store(sampleRequest))

      fakeDefaultdOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe None
      fakeBackgroundOutbox.stored mustBe Some(
        QueuedEmailRequest.from(sampleRequest, renderResult, mockEncryption)
      )
    }

    "store background-priority emails in the background outbox" in new TestCase {
      emailRendererRespondsWithPriority(Some(Priority.background))

      val sampleRequestWithBackgroundAlertQueue: SendEmailRequest = sampleRequest.copy(alertQueue = Some("background"))

      await(router.store(sampleRequestWithBackgroundAlertQueue))

      fakeDefaultdOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe None
      fakeBackgroundOutbox.stored mustBe Some(
        QueuedEmailRequest.from(sampleRequestWithBackgroundAlertQueue, renderResult, mockEncryption)
      )
    }

    "store emails without a priority in the default outbox" in new TestCase {
      emailRendererRespondsWithPriority(None)

      await(router.store(sampleRequest))

      fakeBackgroundOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe None
      fakeDefaultdOutbox.stored mustBe Some(QueuedEmailRequest.from(sampleRequest, renderResult, mockEncryption))
    }

    "handle the scenario of rendering error by returning the messageRenderer error" in new TestCase {
      emailRendererRespondsWithError()

      val result = await(router.store(sampleRequest))

      result mustBe Left(ErrorMessage("errorMessageFromRenderer"))
      fakeBackgroundOutbox.stored mustBe None
      fakeUrgentOutbox.stored mustBe None
      fakeDefaultdOutbox.stored mustBe None
    }

    "encrypt enrolment encrypted if enrolment exists" in new TestCase {
      QueuedEmailRequest
        .from(
          sampleRequest.copy(tags = Map("enrolment" -> "HMRC-CUS-ORG~EORINumber~GB123456789000")),
          renderResult,
          mockEncryption
        )
        .tags mustBe Map("enrolment" -> "encryptedString", "templateId" -> "encryptedString")
    }

    "return remaining tags if there is no enrolment" in new TestCase {
      QueuedEmailRequest
        .from(requestWithoutEnrolment, renderResult, mockEncryption)
        .tags mustBe Map("templateId" -> "encryptedString")
    }

    "return seq of outboxes" in new TestCase {
      when(outboxFactory.create(any, any, any, any)).thenReturn(FakeOutbox(standard))

      router.outboxes.size must be(3)
    }
  }

  val renderConnector: EmailRendererConnector = mock[EmailRendererConnector]
  val emailRepo: EmailQueueRepository = mock[EmailQueueRepository]
  val emailEventsRepository: EmailEventsRepository = mock[EmailEventsRepository]
  val mailgunConnector: MailgunConnector = mock[MailgunConnector]
  val imiConnector: ImiConnector = mock[ImiConnector]
  val auditConnector: AuditConnector = mock[AuditConnector]
  val preSendCheckConnector: PreSendingCheckConnector = mock[PreSendingCheckConnector]
  val holdList: List[Nothing] = List.empty
  val allowList: List[Nothing] = List.empty
  val throttler: WorkThrottling = mock[WorkThrottling]
  val queue: Queue = mock[Queue]
  val sendToImi: SendToImi = mock[SendToImi]
  val sendToMailgun: SendToMailgun = mock[SendToMailgun]

  class FakeOutbox(val priority: Priority)(implicit val hc: HeaderCarrier)
      extends Outbox(
        EMPTY_STRING,
        EMPTY_STRING,
        EMPTY_STRING,
        mock[Encryption],
        ImiConfiguration(true, EMPTY_STRING, EMPTY_STRING),
        renderConnector,
        emailRepo,
        emailEventsRepository,
        mailgunConnector,
        imiConnector,
        auditConnector,
        preSendCheckConnector,
        holdList,
        allowList,
        throttler,
        queue,
        sendToImi,
        sendToMailgun
      ) {
    var stored: Option[QueuedEmailRequest] = None
    override def store(request: QueuedEmailRequest): Future[Either[ErrorMessage, Unit]] = {
      stored = Some(request)
      Future.successful(Right((): Unit))
    }
  }

  class TestCase {

    val outboxFactory: OutboxFactory = mock[OutboxFactory]
    val servicesConfig: ServicesConfig = mock[ServicesConfig]
    val httpClient: HttpClientV2 = mock[HttpClientV2]
    val mockEncryption: Encryption = mock[Encryption]

    import org.mockito.ArgumentMatchers.*

    when(mockEncryption.encrypt(any[String])).thenReturn(Crypted("encryptedString"))

    val sampleRequest: SendEmailRequest = SendEmailRequest(
      to = List(EmailAddress("test@mail.com")),
      templateId = "a template id",
      parameters = Map("param1" -> "key1"),
      tags = Map("enrolment" -> "HMRC-CUS-ORG~EORINumber~GB123456789000"),
      force = false,
      Some("http:/test/url"),
      None,
      auditData = Map("data1" -> "dataValue1")
    )

    val requestWithoutEnrolment: SendEmailRequest = SendEmailRequest(
      to = List(EmailAddress("test@mail.com")),
      templateId = "a template id",
      parameters = Map("param1" -> "key1"),
      tags = Map.empty,
      force = false,
      Some("http:/test/url"),
      None,
      auditData = Map("data1" -> "dataValue1")
    )

    val sampleRequestWithAlertQueue: SendEmailRequest = SendEmailRequest(
      to = List(EmailAddress("test@mail.com")),
      templateId = "a template id",
      parameters = Map("param1" -> "key1"),
      tags = Map.empty,
      force = false,
      Some("http:/test/url"),
      None,
      auditData = Map("data1" -> "dataValue1"),
      alertQueue = Some("PRIORITY")
    )

    val mockEmailRendererConnector: EmailRendererConnector = mock[EmailRendererConnector]

    lazy val fakeUrgentOutbox = new FakeOutbox(Priority.urgent)
    lazy val fakeDefaultdOutbox = new FakeOutbox(Priority.standard)
    lazy val fakeBackgroundOutbox = new FakeOutbox(Priority.background)
    val defaultRate = 125000L
    val backgroundRate = 5000L

    val router: Router = new Router(
      outboxFactory,
      "hmrc",
      SenderDomainConfiguration(
        "tax.service.gov.uk.test",
        "renderer",
        false,
        MailgunApiKeys("a", "b"),
        ImiApiConfig("k1", "g1"),
        None,
        defaultQueue =
          DefaultQueueConfiguration(Some("defaultQueue"), Some(Rate(defaultRate, FiniteDuration(1, "day")))),
        urgentQueue = UrgentQueueConfiguration(Some("urgentQueue")),
        backgroundQueue =
          BackgroundQueueConfiguration(Some("backgroundQueue"), Some(Rate(backgroundRate, FiniteDuration(1, "day")))),
        bounces = BouncesConfiguration(None),
        events = EventsConfiguration(None)
      ),
      SenderDomainDefaultsConfiguration(None),
      servicesConfig,
      httpClient,
      mockEncryption
    ) {
      override lazy val emailRendererConnector: EmailRendererConnector =
        mockEmailRendererConnector
      override lazy val urgentOutbox: Outbox = fakeUrgentOutbox
      override lazy val defaultOutbox: Outbox = fakeDefaultdOutbox
      override lazy val backgroundOutbox: Outbox = fakeBackgroundOutbox
    }

    val renderResult: RenderResult =
      RenderResult("plain", "html", "from@address.com", "subject", "sa", Some("templateId"))

    def emailRendererRespondsWithPriority(priority: Option[Priority]): Unit = {
      val _ = when(
        mockEmailRendererConnector
          .render(any[String], any[Map[String, String]], any[List[EmailAddress]])(any[HeaderCarrier])
      ).thenReturn(Future.successful(Right((priority, renderResult))))
    }

    def emailRendererRespondsWithError(): Unit = {
      val _ = when(
        mockEmailRendererConnector
          .render(any[String], any[Map[String, String]], any[List[EmailAddress]])(any[HeaderCarrier])
      ).thenReturn(Future.successful(Left(new ErrorMessage("errorMessageFromRenderer"))))
    }
  }

}

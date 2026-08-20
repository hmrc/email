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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.mongodb.scala.bson.ObjectId
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.EMPTY_STRING
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.controllers.model.Event
import uk.gov.hmrc.email.model.{ DeliveryStatus, EventMarkingStatus, EventType, ItemSaved, MetricPrefix }
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EmailStatsRepository, EventHubItem, EventHubRepository }
import uk.gov.hmrc.email.utils.EmailStatus.Opened
import uk.gov.hmrc.email.utils.{ EmailStatus, Encryption }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.audit.http.config.AuditingConfig
import uk.gov.hmrc.play.audit.http.connector.{ AuditChannel, AuditConnector, AuditResult, DatastreamMetrics }
import uk.gov.hmrc.play.audit.model.DataEvent
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.time.{ Instant, LocalDateTime, ZoneOffset }
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class EventProcessingSpec extends SpecBase with ScalaFutures {

  val eventHubRepositoryMock: EventHubRepository = mock[EventHubRepository]
  val emailEventsRepositoryMock: EmailEventsRepository = mock[EmailEventsRepository]
  val encryptionMock: Encryption = mock[Encryption]
  val configuration: Configuration = mock[Configuration]
  val servicesConfig: ServicesConfig = mock[ServicesConfig]
  val httpClient: HttpClientV2 = mock[HttpClientV2]
  val actorSystemMock: ActorSystem = mock[ActorSystem]
  val httpAuditing: HttpAuditing = mock[HttpAuditing]
  val senderDomainConfigurationLoader: SenderDomainConfigurationLoader = mock[SenderDomainConfigurationLoader]
  val emailStatsRepository: EmailStatsRepository = mock[EmailStatsRepository]

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

  val eventProcessing = new EventProcessing(
    eventHubRepositoryMock,
    emailEventsRepositoryMock,
    senderDomainConfigurationLoader,
    httpClient,
    servicesConfig,
    encryptionMock,
    fakeAuditConnector,
    emailStatsRepository
  )

  val messageId: UUID = UUID.randomUUID()
  val correlationId: UUID = UUID.randomUUID()
  val emailAddress = "test@example.com"
  val timeStamp: LocalDateTime = LocalDateTime.now()
  val description = "Delivered"
  val code = "123"

  def generateEvent(status: DeliveryStatus, description: String, addtionalInfo: String = EMPTY_STRING): Event =
    Event(
      messageId = messageId,
      correlationId = correlationId,
      emailAddress = emailAddress,
      timeStamp = timeStamp,
      status = status,
      description = description,
      tags = Map("ContactPolicyGroupId" -> "something"),
      code = code,
      additionalInfo = addtionalInfo
    )

  val hashString: String = eventProcessing.getHashString(generateEvent(DeliveryStatus.Read, "Delivered"))

  val item: WorkItem[EmailEventsItem] = WorkItem(
    new ObjectId(),
    Instant.now(),
    Instant.now(),
    Instant.now(),
    ProcessingStatus.InProgress,
    0,
    EmailEventsItem(messageId.toString, None, Map(EventType.Sent -> Instant.now()), None, EMPTY_STRING)
  )

  "EventProcessing" must {
    "push event to event hub repository and mark email event and event-hub has senderDomain as a tag" in {
      val randomEventId = UUID.randomUUID()
      when(emailEventsRepositoryMock.findEvent(any[String])).thenReturn(Future.successful(Some(item)))
      when(eventHubRepositoryMock.pushEventHubItem(any[EventHubItem])).thenReturn(Future.successful(ItemSaved))
      when(emailEventsRepositoryMock.markEvent(any[String], any[EventType], any[Instant]))
        .thenReturn(Future.successful(EventMarkingStatus.Marked))
      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus])).thenReturn(Future.successful(()))
      when(senderDomainConfigurationLoader.default).thenReturn(
        Map(
          "hmrc" ->
            SenderDomainConfiguration(
              "tax.service.test1",
              "r1",
              false,
              MailgunApiKeys("k1", "k2"),
              ImiApiConfig("k1", "g1"),
              None,
              DefaultQueueConfiguration(None, None),
              UrgentQueueConfiguration(None),
              BackgroundQueueConfiguration(None, None),
              BouncesConfiguration(Some("bounce")),
              EventsConfiguration(None)
            )
        )
      )

      val event = generateEvent(DeliveryStatus.Read, "Delivered")
      val result = eventProcessing.apply(event, "fromTest", randomEventId).futureValue

      verify(eventHubRepositoryMock, times(1)).pushEventHubItem(
        EventHubItem(
          id = messageId.toString,
          eventId = randomEventId,
          emailAddress = emailAddress,
          detected = timeStamp.toInstant(ZoneOffset.UTC),
          eventType = Opened.toString,
          reason = description,
          tags = Map("senderDomain" -> EMPTY_STRING, "correlationId" -> event.correlationId.toString),
          code = Some(code.toInt),
          messageId = messageId.toString,
          hash = hashString,
          template = None
        )
      )
      verify(emailEventsRepositoryMock)
        .markEvent(messageId.toString, EventType.Opened, timeStamp.toInstant(ZoneOffset.UTC))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        EMPTY_STRING,
        EmailStatus.Opened
      )

      result mustBe (EventMarkingStatus.Marked)
    }

    "return event as Marked, when there is no event in email_events and eventType is Read" in {
      val randomEventId = UUID.randomUUID()
      when(emailEventsRepositoryMock.findEvent(any[String])).thenReturn(Future.successful(None))
      when(eventHubRepositoryMock.pushEventHubItem(any[EventHubItem])).thenReturn(Future.successful(ItemSaved))
      when(emailEventsRepositoryMock.markEvent(any[String], any[EventType], any[Instant]))
        .thenReturn(Future.successful(EventMarkingStatus.Marked))
      when(senderDomainConfigurationLoader.default).thenReturn(
        Map(
          "hmrc" ->
            SenderDomainConfiguration(
              "tax.service.test1",
              "r1",
              false,
              MailgunApiKeys("k1", "k2"),
              ImiApiConfig("k1", "g1"),
              None,
              DefaultQueueConfiguration(None, None),
              UrgentQueueConfiguration(None),
              BackgroundQueueConfiguration(None, None),
              BouncesConfiguration(Some("bounce")),
              EventsConfiguration(None)
            )
        )
      )

      val event = generateEvent(DeliveryStatus.Read, "Delivered")
      val result = eventProcessing.apply(event, "fromTest", randomEventId)

      result.futureValue mustBe (EventMarkingStatus.Marked)
    }

    "return event as marked, when there is no event in email_events" in {
      val randomEventId = UUID.randomUUID()
      when(emailEventsRepositoryMock.findEvent(any[String])).thenReturn(Future.successful(None))
      when(eventHubRepositoryMock.pushEventHubItem(any[EventHubItem])).thenReturn(Future.successful(ItemSaved))
      when(emailEventsRepositoryMock.markEvent(any[String], any[EventType], any[Instant]))
        .thenReturn(Future.successful(EventMarkingStatus.Marked))
      when(senderDomainConfigurationLoader.default).thenReturn(
        Map(
          "hmrc" ->
            SenderDomainConfiguration(
              "tax.service.test1",
              "r1",
              false,
              MailgunApiKeys("k1", "k2"),
              ImiApiConfig("k1", "g1"),
              None,
              DefaultQueueConfiguration(None, None),
              UrgentQueueConfiguration(None),
              BackgroundQueueConfiguration(None, None),
              BouncesConfiguration(Some("bounce")),
              EventsConfiguration(None)
            )
        )
      )

      val event = generateEvent(DeliveryStatus.Delivered, "Delivered")
      val result = eventProcessing.apply(event, "fromTest", randomEventId).futureValue
      result mustBe EventMarkingStatus.Marked
    }
  }

  "getEventType function" must {

    "return EventType Accepted for DeliveryStatus.Submitted" in {
      val result = eventProcessing.getEventType(generateEvent(DeliveryStatus.Submitted, "Submitted"))
      result mustBe (EventType.Accepted)
    }

    "return EventType Opened for DeliveryStatus.Read" in {
      val result = eventProcessing.getEventType(generateEvent(DeliveryStatus.Read, "Read"))
      result mustBe (EventType.Opened)
    }

    "return EventType Delivered for DeliveryStatus.Delivered" in {
      val result = eventProcessing.getEventType(generateEvent(DeliveryStatus.Delivered, "Delivered"))
      result mustBe (EventType.Delivered)
    }

    "return EventType PermanentBounce for DeliveryStatus.Bounce, description Transient_General and additionalInfo contains Invalid domain" in {
      val result =
        eventProcessing.getEventType(
          generateEvent(DeliveryStatus.Bounce, "Transient_General", "5.4.4|failed|smtp; 550 5.4.4 Invalid domain")
        )
      result mustBe (EventType.PermanentBounce)
    }

    "return EventType TemporaryBounce for DeliveryStatus.Bounce and description Transient_General" in {
      val result =
        eventProcessing.getEventType(generateEvent(DeliveryStatus.Bounce, "Transient_General"))
      result mustBe (EventType.TemporaryBounce)
    }

    "return EventType PermanentBounce for DeliveryStatus.Bounce and any other description other than Transient_General" in {
      val result = eventProcessing.getEventType(generateEvent(DeliveryStatus.Bounce, "Transient_ContentRejected"))
      result mustBe (EventType.PermanentBounce)
    }

    "return EventType TemporaryBounce for DeliveryStatus.Failed(9004) other than description `Recipient has not consented to message`" in {
      val result = eventProcessing.getEventType(
        generateEvent(DeliveryStatus.Failed, "Unable to reach Contact Policy Service, please try again later")
      )
      result mustBe (EventType.TemporaryBounce)
    }

    "return EventType PermanentBounce for DeliveryStatus.Failed(9002) and description `Recipient has not consented to message`" in {
      val result =
        eventProcessing.getEventType(generateEvent(DeliveryStatus.Failed, "Recipient has not consented to message"))
      result mustBe (EventType.PermanentBounce)
    }

    "return EventType Complained for DeliveryStatus.Complained" in {
      val result =
        eventProcessing.getEventType(generateEvent(DeliveryStatus.Complained, "Complained"))
      result mustBe (EventType.Complained)
    }
  }

  "isPermanentBounce function" must {
    "return true if addtionalInfo has space between smtp and 550" in {
      val result = eventProcessing.isPermanentBounce(
        "2023-06-21 05:14:28.293Z - 4.4.7|failed|smtp; 550 4.4.7 Message expired: unable to deliver in 840 minutes.<421 4.4.0 Unable to lookup DNS for innovateaccountancylimitede.co.uk>"
      )
      result mustBe true
    }

    "return true if addtionalInfo has no space between smtp and 550" in {
      val result = eventProcessing.isPermanentBounce(
        "2023-06-20 14:30:21.339Z - 5.7.133|failed|smtp;550 5.7.133 RESOLVER.RST.SenderNotAuthenticatedForGroup; authentication required; Delivery restriction check failed because the sender was not authenticated when sending to this group"
      )
      result mustBe true
    }

    "return true if addtionalInfo has space between upper SMTP and 550" in {
      val result = eventProcessing.isPermanentBounce(
        "2023-06-19 16:01:35.222Z - 5.7.1|failed|SMTP; 550-5.7.1 The user or domain that you are sending to (or from) has a policy that"
      )
      result mustBe true
    }

    "return true if addtionalInfo has no space between upper SMTP and 550" in {
      val result = eventProcessing.isPermanentBounce(
        "2023-06-19 16:01:35.222Z - 5.7.1|failed|SMTP;550-5.7.1 The user or domain that you are sending to (or from) has a policy that"
      )
      result mustBe true
    }

    "return false if addtionalInfo is empty" in {
      val result = eventProcessing.isPermanentBounce(EMPTY_STRING)
      result mustBe false
    }

    "return false if addtionalInfo has double pipes" in {
      val result = eventProcessing.isPermanentBounce("||")
      result mustBe false
    }
  }

  "scrapeEmail" must {
    "scrape single email in text" in {
      val text = "already bounced : test@wags.co.uk"
      val scrapedText = eventProcessing.scrapeEmails(text)

      scrapedText mustBe "already bounced : emailHidden"
    }
    "scrape multiple emails in text" in {
      val text =
        "already bounced : test@wags.co.uk some infinite text and an email appears test@gmail.com and then it appears again lee@willhill.co.uk"
      val scrapedText = eventProcessing.scrapeEmails(text)
      scrapedText mustBe "already bounced : emailHidden some infinite text and an email appears emailHidden and then it appears again emailHidden"
    }
  }

  "saveStats" must {
    "save Opened event" in {
      val _ = generateEvent(DeliveryStatus.Read, "some description")
      val _ = eventProcessing.saveStats(Some("some.domain"), EventType.Opened)
      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus]))
        .thenReturn(Future.successful(()))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        "some-domain",
        EmailStatus.Opened
      )
    }

    "save Delivered event" in {
      val _ = generateEvent(DeliveryStatus.Read, "some description")
      val _ = eventProcessing.saveStats(Some("some.domain"), EventType.Delivered)
      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus]))
        .thenReturn(Future.successful(()))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        "some-domain",
        EmailStatus.Delivered
      )
    }

    "save Accepted event" in {
      val _ = generateEvent(DeliveryStatus.Read, "some description")
      val _ = eventProcessing.saveStats(Some("some.domain"), EventType.Accepted)
      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus]))
        .thenReturn(Future.successful(()))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        "some-domain",
        EmailStatus.Accepted
      )
    }

    "save Bounced event" in {
      val _ = generateEvent(DeliveryStatus.Bounce, "some description")
      val _ = eventProcessing.saveStats(Some("some.domain"), EventType.PermanentBounce)
      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus]))
        .thenReturn(Future.successful(()))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        "some-domain",
        EmailStatus.Bounced
      )
    }

    "save TemporaryBounce event" in {
      val _ = generateEvent(DeliveryStatus.Failed, "some description")
      val _ = eventProcessing.saveStats(Some("some.domain"), EventType.TemporaryBounce)

      when(emailStatsRepository.put(any[MetricPrefix], any[String], any[EmailStatus]))
        .thenReturn(Future.successful(()))
      verify(emailStatsRepository, times(1)).put(
        MetricPrefix.Domain,
        "some-domain",
        EmailStatus.TemporaryBounce
      )
    }
  }

}

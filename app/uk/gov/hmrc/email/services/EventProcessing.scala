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

import org.apache.commons.codec.binary.Base64
import play.api.libs.json.JsValue
import play.api.Logging
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.connectors.ImiConnector
import uk.gov.hmrc.email.controllers.model.Event
import uk.gov.hmrc.email.model.EventMarkingStatus.Marked
import uk.gov.hmrc.email.model.EventType.{ Accepted, Complained, Delivered, Opened, PermanentBounce, TemporaryBounce }
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EmailStatsRepository, EventHubItem, EventHubRepository }
import uk.gov.hmrc.email.utils.{ EmailStatus, Encryption, TemplateIdFormMapping }
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.matching.Regex
import scala.util.{ Success, Try }

class EventProcessing @Inject() (
  eventHubRepository: EventHubRepository,
  emailEventsRepository: EmailEventsRepository,
  senderDomainConfigurationLoader: SenderDomainConfigurationLoader,
  httpClient: HttpClientV2,
  servicesConfig: ServicesConfig,
  encryption: Encryption,
  audit: AuditConnector,
  emailStatsRepository: EmailStatsRepository
)(implicit ec: ExecutionContext)
    extends Logging {

  lazy val deliveredEventExpiryDays =
    servicesConfig.getInt("events-expiry.deliveredEventInDays")

  def apply(
    event: Event,
    from: String = "email_events",
    randomEventId: UUID = UUID.randomUUID()
  ): Future[EventMarkingStatus] = {

    logger.warn(s"EventProcessedVia $from for ${event.messageId}")

    val eventType = getEventType(event)

    if (eventType.name == EventType.PermanentBounce.name && event.additionalInfo.nonEmpty)
      logger.warn(s"PermanentBounce additionalInfo is ${scrapeEmails(event.additionalInfo)}}")

    val eventHubItem = EventHubItem(
      id = event.messageId.toString,
      eventId = randomEventId,
      emailAddress = event.emailAddress,
      detected = event.timeStamp.toInstant(ZoneOffset.UTC),
      eventType = eventType,
      reason = event.description,
      tags = decryptTags(event.tags),
      code = Try(event.code.toInt).toOption,
      messageId = event.messageId.toString,
      hash = getHashString(event),
      template = None
    )

    val eventHubItemToProcess = eventHubItemWithTags(eventHubItem, event, eventType)

    val possiblyAddToContactPolicy = possiblyInsertToContactPolicy(event, eventType)
    val logStatsForBounce = saveStatsForPermanentBounce(eventType, eventHubItem)
    val eventActions: Future[(EventMarkingStatus, Option[String])] = eventHubItemToProcess.flatMap {
      case Some(item) =>
        val domain = item.tags.get("senderDomain")
        pushToEventHubAndMarkIt(item, event, eventType).map(markingStatus => (markingStatus, domain))
      case None => Future.successful((Marked, None))
    }
    val logStatsForEvent = eventActions.map(s => saveStats(s._2, eventType))

    for {
      _      <- possiblyAddToContactPolicy.zip(logStatsForBounce)
      _      <- logStatsForEvent
      result <- eventActions
    } yield result._1
  }

  private[services] def isPermanentBounce(additionalInfo: String): Boolean = {
    val items = additionalInfo.split("\\|")
    items.lift(2).exists(item => item.toLowerCase.filterNot(_.isWhitespace).startsWith("smtp;5"))
  }

  private[services] def getEventType(event: Event): EventType =
    event.status match {
      case DeliveryStatus.Submitted => Accepted
      case DeliveryStatus.Read      => Opened
      case DeliveryStatus.Delivered => Delivered
      case DeliveryStatus.Bounce
          if event.description == "Transient_General" && isPermanentBounce(event.additionalInfo) =>
        PermanentBounce
      case DeliveryStatus.Bounce if event.description == "Transient_General"                      => TemporaryBounce
      case DeliveryStatus.Bounce                                                                  => PermanentBounce
      case DeliveryStatus.Failed if event.description == "Recipient has not consented to message" => PermanentBounce
      case DeliveryStatus.Failed                                                                  => TemporaryBounce
      case DeliveryStatus.Complained                                                              => Complained

      case _ =>
        throw new IllegalArgumentException(s"Unrecognised event status: ${event.status}")
    }

  private[services] def getHashString(event: Event) = {
    val sha256Digester = MessageDigest.getInstance("SHA-256")
    new String(
      Base64.encodeBase64(
        sha256Digester.digest(
          Seq(
            event.messageId.toString,
            event.correlationId.toString,
            event.emailAddress,
            event.timeStamp.toString,
            getEventType(event).name,
            event.description,
            event.tags.toString(),
            event.code
          ).mkString("/").getBytes("UTF-8")
        )
      )
    )
  }

  private def decryptTags(tags: Map[String, String]) = {
    val filteredTags = tags.removed("ContactPolicyGroupId")
    filteredTags.map { case (key, value) =>
      val decryptedValue: String = Try(encryption.decrypt(value)) match {
        case Success(text) => text.value
        case _             => value
      }
      (key, decryptedValue)
    }
  }

  private def possiblyInsertToContactPolicy(event: Event, eventType: EventType) =
    if (event.code != "9002" && eventType == EventType.PermanentBounce) {
      def baseUrl(serviceName: String): String = servicesConfig.baseUrl(serviceName)
      val senderDomainConfiguration = senderDomainConfigurationLoader.default("hmrc")
      val consentKey = servicesConfig.getString("imi.consentServiceKey")
      val imiConnector =
        new ImiConnector(
          senderDomainConfiguration,
          httpClient,
          consentKey,
          baseUrl("imi"),
          baseUrl("imi-consent"),
          audit
        )

      val additionalInfo = if (event.additionalInfo.isEmpty) event.code else event.additionalInfo.take(400)

      event.tags.get("ContactPolicyGroupId") match {
        case Some(groupId) =>
          imiConnector.addConsent(event.emailAddress, groupId, false, additionalInfo)
        case _ =>
          Future.successful(true)

      }
    } else Future.successful(false)

  private[services] def scrapeEmails(text: String) = {
    val emailRegex: Regex = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b".r
    emailRegex.findAllIn(text).toList.foldLeft(text)((acc, email) => acc.replace(email, "emailHidden"))
  }

  private def eventHubItemWithTags(eventHubItem: EventHubItem, event: Event, eventType: EventType) =
    emailEventsRepository.findEvent(event.messageId.toString).map {
      case Some(value) =>
        logger.warn(s"eventItem is $value")
        Some(
          eventHubItem
            .copy(tags =
              eventHubItem.tags ++
                Map("senderDomain" -> value.item.senderDomain, "correlationId" -> event.correlationId.toString)
            )
        )

      case None if eventType == EventType.Opened =>
        logger.warn(
          s"Read Event ${eventHubItem.id} is possibly old, this event may be" +
            s" received after email is delivered more than $deliveredEventExpiryDays days ago"
        )
        None
      case None =>
        logger.error(
          s"Event with type $eventType failed to update, this should never happen for transId ${event.messageId.toString}"
        )
        None
    }

  private def pushToEventHubAndMarkIt(item: EventHubItem, event: Event, eventType: EventType) =
    eventHubRepository.pushEventHubItem(item).flatMap { result =>
      if (result == ItemSaved)
        emailEventsRepository
          .markEvent(event.messageId.toString, eventType, event.timeStamp.toInstant(ZoneOffset.UTC))
      else
        Future.successful(Marked)
    }

  private def parseDomain(domain: String) = domain.replace(".", "-")

  private[services] def saveStats(senderDomain: Option[String], eventType: EventType): Future[Unit] = {

    val emailStatus: Option[EmailStatus] = eventType match {
      case PermanentBounce => Some(EmailStatus.Bounced)
      case TemporaryBounce => Some(EmailStatus.TemporaryBounce)
      case Delivered       => Some(EmailStatus.Delivered)
      case Opened          => Some(EmailStatus.Opened)
      case Accepted        => Some(EmailStatus.Accepted)
      case Complained      => Some(EmailStatus.Complained)
      case _               => None
    }
    (emailStatus, senderDomain) match
      case (Some(status), Some(domain)) => emailStatsRepository.put(MetricPrefix.Domain, parseDomain(domain), status)
      case _                            => Future.successful(())
  }

  private def saveStatsForPermanentBounce(eventType: EventType, event: EventHubItem) = {
    val templateId = event.tags
      .get("templateId")
      .fold {
        logger.error(s"PermanentBounce event ${event.id} does not have templateId which should never happen")
        ""
      }(_.toString)
    eventType match {
      case PermanentBounce =>
        TemplateIdFormMapping.mapping
          .get(templateId)
          .fold(Future.successful(()))(formId =>
            emailStatsRepository.put(MetricPrefix.FormId, formId.toLowerCase, EmailStatus.Bounced)
          )
      case _ => Future.successful(())
    }
  }

  private[services] def readEvents(json: JsValue) =
    json.as[RawEvent]

  def findEvent(transId: String): Future[Option[String]] =
    emailEventsRepository.findEvent(transId).map(_.map(_.toString))

}

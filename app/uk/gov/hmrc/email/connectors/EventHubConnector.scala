/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import play.api.Logging
import play.api.http.Status
import play.api.libs.json.*
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.email.model.Keys
import uk.gov.hmrc.email.repositories.EventHubItem
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.{ AuditConnector, AuditResult }
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{ Instant, LocalDateTime }
import java.util.UUID
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

@Singleton
class EventHubConnector @Inject() (
  serviceConfig: ServicesConfig,
  httpClient: HttpClientV2,
  auditConnector: AuditConnector
)(implicit ec: ExecutionContext)
    extends Logging {

  implicit val legacyRawReads: HttpReads[HttpResponse] =
    HttpReads.Implicits.throwOnFailure(HttpReads.Implicits.readEitherOf(using HttpReads.Implicits.readRaw))

  def baseUrl: String = serviceConfig.baseUrl("event-hub")
  def uriPath: String = serviceConfig.getString("streams.event-hub.uri.path")

  def publishEventHubItem(
    eventHubItem: EventHubItem
  )(implicit hc: HeaderCarrier): Future[Either[ErrorMessage, EventHubResponse]] = {

    logger.warn(s"Publishing event hub item to event-hub service for messageId ${eventHubItem.messageId}...")
    val eventHubRequestBody = EventHubRequest(
      eventId = eventHubItem.eventId,
      subject = "email",
      groupId = eventHubItem.messageId,
      timestamp = LocalDateTime.now,
      event = RequestEventHubItem(eventHubItem)
    )

    httpClient.post(s"$baseUrl$uriPath").withBody(Json.toJson(eventHubRequestBody)).execute[HttpResponse].map {
      result =>
        logger.debug(s"Response from event-hub for messageId ${eventHubItem.messageId}: ${result.body}")
        auditEventHubItem(eventHubItem, result.status, eventHubRequestBody.event)
        Right(EventHubResponse(result.body))
    } recover { case e: Exception =>
      logger.error(s"Event-hub Connector POST error for messageId ${eventHubItem.messageId}: ${e.getMessage}")
      auditEventHubItem(eventHubItem, 0, eventHubRequestBody.event)
      Left(ErrorMessage.fromExceptionReason(e.getMessage))
    }
  }

  private def createBouncedDataEvent(
    event: EventHubItem,
    transactionName: String,
    statusCode: Int,
    item: RequestEventHubItem
  ): DataEvent = {
    val auditType = statusCode match {
      case Status.CREATED => EventTypes.Succeeded
      case _              => EventTypes.Failed
    }

    val messageKey = event.tags.get(Keys.MESSAGE_KEY)

    val eventDetails = Map(
      "eventId"        -> event.eventId.toString,
      "emailMessageId" -> event.messageId,
      "detected"       -> DateTimeFormatter.ISO_INSTANT.format(event.detected.truncatedTo(ChronoUnit.MILLIS)),
      "emailAddress"   -> event.emailAddress,
      "emailEventId"   -> event.id,
      "statusCode"     -> statusCode.toString,
      "tags"           -> event.tags.mkString(","),
      "templateId"     -> event.tags.getOrElse("templateId", "")
    )

    DataEvent(
      auditSource = "email",
      auditType = auditType,
      tags = Map(
        EventKeys.TransactionName -> transactionName,
        "requestItem"             -> Json.toJson[RequestEventHubItem](item).toString()
      ),
      detail = messageKey.fold(eventDetails)(key => eventDetails + (Keys.MESSAGE_ID -> key))
    )
  }

  private def auditEventHubItem(event: EventHubItem, statusCode: Int, requestItem: RequestEventHubItem)(implicit
    ec: ExecutionContext
  ): Future[AuditResult] = {
    val transactionName = event.eventType match {
      case "PermanentBounce" => "Permanent Bounced"
      case "TemporaryBounce" => "Temporarily Bounced"
      case "Rejected"        => "Rejected"
      case otherEventType    => otherEventType
    }
    val dataEvent = createBouncedDataEvent(event, transactionName, statusCode, requestItem)
    auditConnector.sendEvent(dataEvent)
  }
}

case class RequestEventHubItem(
  id: String,
  emailAddress: String,
  detected: Instant,
  event: String,
  reason: String,
  tags: Map[String, String],
  code: Option[Int]
)
object RequestEventHubItem {
  implicit val requestEventHubItem: OFormat[RequestEventHubItem] = Json.format[RequestEventHubItem]
  def apply(eventHubItem: EventHubItem): RequestEventHubItem =
    RequestEventHubItem(
      eventHubItem.id,
      eventHubItem.emailAddress,
      eventHubItem.detected,
      eventHubItem.eventType,
      eventHubItem.reason,
      eventHubItem.tags,
      eventHubItem.code
    )
}

final case class EventHubRequest(
  eventId: UUID,
  subject: String,
  groupId: String,
  timestamp: LocalDateTime,
  event: RequestEventHubItem
)
object EventHubRequest {
  implicit val eventHubRequestFormat: OFormat[EventHubRequest] = Json.format[EventHubRequest]
}

final case class EventHubResponse(message: String)
object EventHubResponse {
  implicit val eventHubResponseFormat: OFormat[EventHubResponse] = Json.format[EventHubResponse]
}

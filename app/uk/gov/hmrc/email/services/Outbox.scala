/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.mongodb.scala.result.DeleteResult
import play.api.Logging
import uk.gov.hmrc.clusterworkthrottling.WorkThrottling
import uk.gov.hmrc.email.connectors.*
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.repositories.*
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.utils.{ CorrelationId, Encryption }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.workitem.WorkItem
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ ExecutionContext, Future }

case class Outbox(
  senderDomain: String,
  domainName: String,
  queueName: String,
  encryption: Encryption,
  imiConfiguration: ImiConfiguration,
  emailTemplateRendererConnector: EmailRendererConnector,
  emailRepository: EmailQueueRepository,
  emailEventsRepository: EmailEventsRepository,
  mailgunConnector: MailgunConnector,
  imiConnector: ImiConnector,
  auditConnector: AuditConnector,
  preSendingCheck: PreSendingCheckConnector,
  holdList: List[RecipientDomainPattern],
  allowList: List[RecipientDomainPattern],
  throttler: WorkThrottling,
  queue: Queue,
  sendToImi: SendToImi,
  sendToMailgun: SendToMailgun
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging {

  def store(request: QueuedEmailRequest): Future[Either[ErrorMessage, Unit]] =
    queue.add(request, imiConfiguration.useImiConnector)
  def sendAll(implicit hc: HeaderCarrier): Future[EmailQueueProcessingResults] = {
    val pullWorkItems: Source[WorkItem[QueuedEmailRequest], NotUsed] = Source.unfoldAsync(()) { _ =>
      pullPendingEmailWhile(continue).map(_.map(((), _)))
    }
    val counter = Sink.foldAsync[EmailQueueProcessingResults, WorkItem[QueuedEmailRequest]] {
      EmailQueueProcessingResults.empty
    } {
      throttledSend(_, _)(hc)
    }
    pullWorkItems.runWith(counter)
  }
  private[services] def throttledSend(
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest],
    correlationId: CorrelationId = CorrelationId()
  )(implicit hc: HeaderCarrier): Future[EmailQueueProcessingResults] =
    if (imiConfiguration.useImiConnector)
      sendToImi.throttledSend(current, pendingEmail, correlationId)
    else sendToMailgun.throttledSend(current, pendingEmail)
  def removeAll(): Future[DeleteResult] =
    emailRepository.collection.deleteMany(Filters.empty()).toFuture()

  val running = new AtomicBoolean(true)

  def continue: Boolean = running.get()

  def cancel(): Unit = running.set(false)

  private def pullPendingEmailWhile(continue: => Boolean): Future[Option[WorkItem[QueuedEmailRequest]]] =
    if (continue)
      emailRepository.pullPendingEmail()
    else
      Future.successful(None)
}

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

import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.clusterworkthrottling.WorkThrottling
import uk.gov.hmrc.email.connectors.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.repositories.*
import uk.gov.hmrc.email.repositories.model.{ EmailEventsItem, QueuedEmailRequest }
import uk.gov.hmrc.email.utils.{ CorrelationId, EmailStatus, Encryption, TemplateIdFormMapping }
import uk.gov.hmrc.http.{ BadRequestException, HeaderCarrier }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@Singleton
class SendToImi @Inject() (
  senderDomain: String,
  domainName: String,
  encryption: Encryption,
  imiConfiguration: ImiConfiguration,
  emailRepository: EmailQueueRepository,
  emailEventsRepository: EmailEventsRepository,
  imiConnector: ImiConnector,
  auditConnector: AuditConnector,
  preSendingCheck: PreSendingCheckConnector,
  holdList: List[RecipientDomainPattern],
  allowList: List[RecipientDomainPattern],
  throttler: WorkThrottling,
  emailStatsRepository: EmailStatsRepository
)(implicit ex: ExecutionContext)
    extends SendEmail(
      encryption: Encryption,
      imiConfiguration: ImiConfiguration,
      holdList: List[RecipientDomainPattern],
      allowList: List[RecipientDomainPattern]
    ) with Logging {

  def throttledSend(
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest],
    correlationId: CorrelationId
  )(implicit hc: HeaderCarrier): Future[EmailQueueProcessingResults] =
    throttler.throttledStartingFrom(pendingEmail.updatedAt) {
      pendingEmail.item.onSendUrl match {
        case Some(url) => checkAndSendEmail(url, current, pendingEmail, correlationId)
        case None =>
          sendEmail(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest], correlationId)
      }
    }

  private[services] def emailContent(
    request: QueuedEmailRequest,
    renderResult: RenderResult,
    correlationId: String,
    groupId: String,
    notifyUrl: String
  ) =
    EmailContent(
      Channel.EMAIL,
      s"noreply@$domainName",
      List(To(request.to.map(a => EmailAddress(a)), correlationId)),
      tags(request, renderResult),
      Options(false, true, fromSubject(renderResult)),
      ContactPolicy(groupId, true, true),
      Seq("submitted", "delivered", "not verified", "invalid", "bounce", "complaint", "read", "failed"),
      Content("html", renderResult.subject, None, renderResult.plain, renderResult.html),
      notifyUrl
    )

  private def fromSubject(renderResult: RenderResult) = {
    val fromAddress = renderResult.fromAddress
    domainName.contains("developer") match {
      case false => fromAddress.replace(s"<noreply@$domainName>", "").trim
      case true  => fromAddress.replace("developer.", "").replace(s"<noreply@tax.service.gov.uk>", "").trim
    }
  }

  private def consentEmail(addressList: Seq[EmailAddress]) =
    Future.sequence {
      addressList.map { address =>
        imiConnector.getConsent(address, imiConfiguration.imiGroupId).flatMap {
          case Some(item) if !item.consent =>
            imiConnector.deleteConsent(address, imiConfiguration.imiGroupId)
          case _ => Future.successful(true)
        }
      }
    }

  private def processForceFlag(forceFlag: Boolean, to: Seq[EmailAddress]) = {
    val addresses = if (forceFlag) to else Seq.empty
    consentEmail(addresses)
  }

  private def badRequestHandler(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest]) =
    emailRepository
      .complete(pendingEmail.id)
      .map(_ => current.incrementPermanentlyFailed)

  private def verifyAndSend(
    request: QueuedEmailRequest,
    renderResult: RenderResult,
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest],
    correlationId: CorrelationId
  ): Future[EmailQueueProcessingResults] = {
    logger.warn(s"checkAndSendEmail ${request.renderedEmail.map(_.subject)}")
    val heldMatchingDomains = checkHoldList(request.to)
    val allowListedEmailAddresses = checkAllowList(request.to)
    val updatedRequest = request.copy(to = allowListedEmailAddresses)

    logger.warn(s"checkAndSendEmailDomain  $domainName")
    logger.warn(s"checkAndSendFromAddress  ${renderResult.fromAddress}")
    logger.warn(s"checkAndSendEmailTemplateId ${request.templateId}")

    if (allowListedEmailAddresses.isEmpty) {
      logger.warn(s"Failed to sent email. ${request.to.mkString(",")} not allowListed. It will not be retried.")
      emailRepository.complete(pendingEmail.id).map(_ => current)
    } else if (heldMatchingDomains.nonEmpty)
      Future.failed(new RuntimeException(s"${heldMatchingDomains.mkString(",")} violated the hold list"))
    else
      for {
        _ <- processForceFlag(updatedRequest.force, updatedRequest.to)
        _ <- send(
               emailContent(
                 request,
                 renderResult,
                 correlationId.value.toString,
                 imiConfiguration.imiGroupId,
                 imiConfiguration.notifyUrl
               ),
               updatedRequest
             )
        _ <- emailRepository.complete(pendingEmail.id)
      } yield current.incrementSent
  }

  private def markAsFailed(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest]) =
    emailRepository
      .markAs(pendingEmail.id, ProcessingStatus.Failed)
      .map(_ => current.incrementRequeued)

  private def sendEmail(
    current: EmailQueueProcessingResults,
    queueItem: WorkItem[QueuedEmailRequest],
    correlationId: CorrelationId
  ) = {
    val request = queueItem.item
    request.renderedEmail match {
      case Some(rendered) =>
        verifyAndSend(request, rendered, current, queueItem, correlationId)
          .recoverWith { case _: BadRequestException =>
            badRequestHandler(current, queueItem)
          }
          .recoverWith { case e =>
            logger.warn(s"Failed to process email: ${e.getMessage}")
            markAsFailed(current, queueItem)
          }
      case None =>
        logger.warn(s"handleLegacyUnrenderedEmailRequest called for ${request.renderedEmail.map(_.subject)}")
        throw new RuntimeException("renderer should never be empty")
    }

  }
  private def checkAndSendEmail(
    url: String,
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest],
    correlationId: CorrelationId
  )(implicit hc: HeaderCarrier) =
    preSendingCheck
      .shouldISend(url)
      .flatMap {
        case Right(SendAlertResponse(true)) =>
          sendEmail(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest], correlationId)
        case Right(SendAlertResponse(false)) =>
          emailRepository.complete(pendingEmail.id).map { _ =>
            current.incrementAborted
          }
        case Left(true) =>
          logger.warn(s"Failed pre-send check calling $url for pending email template ${pendingEmail.item.templateId}")
          emailRepository.markAs(pendingEmail.id, ProcessingStatus.PermanentlyFailed).map { _ =>
            current.incrementPermanentlyFailed
          }
        case Left(false) =>
          logger.warn(s"Failed pre-send check calling $url for pending email template ${pendingEmail.item.templateId}")
          emailRepository.markAs(pendingEmail.id, ProcessingStatus.Failed).map { _ =>
            current.incrementRequeued
          }
      }
      .recoverWith { case e =>
        logger.warn(
          s"Failed pre-send check calling $url for pending email template ${pendingEmail.item.templateId}: ${e.getMessage}",
          e
        )
        emailRepository.markAs(pendingEmail.id, ProcessingStatus.Failed).map { _ =>
          current.incrementRequeued
        }
      }

  private[services] def send(emailContent: EmailContent, queuedRequest: QueuedEmailRequest) = {
    logger.warn(s"Sending email to ${queuedRequest.templateId}")

    imiConnector
      .send(emailContent)
      .flatMap {
        case Right(sent) =>
          val auditFuture = auditSend(sent, queuedRequest)
          val markSentFuture = markSent(sent.messageId, queuedRequest.eventUrl, queuedRequest.emailSource, domainName)
          val metricFuture = TemplateIdFormMapping.mapping
            .get(queuedRequest.templateId)
            .fold(Future.successful(()))(formId =>
              emailStatsRepository.put(MetricPrefix.FormId, formId.toLowerCase, EmailStatus.Sent)
            )
          logger.warn(s"metric updated for formId ${TemplateIdFormMapping.mapping
              .get(queuedRequest.templateId)}")
          Future.sequence(Seq(auditFuture, markSentFuture, metricFuture)).map(_ => ())

        case Left(error) =>
          logger.error(s"Imi Error: ${error.errorType} ${error.message}")
          throw new RuntimeException(s"${error.errorType} ${error.message}")
      }
  }

  def markSent(
    messageId: String,
    eventUrl: Option[String],
    emailSource: Option[String],
    senderDomain: String,
    times: Int = 1
  ): Future[WorkItem[EmailEventsItem]] =
    if (times <= 4)
      emailEventsRepository
        .markSent(messageId, eventUrl, emailSource, senderDomain)
        .recoverWith { case e: Exception =>
          logger.error(
            s"MarkSentFailed Exception: For $messageId with attempt number $times with message ${e.getMessage}"
          )
          markSent(messageId, eventUrl, emailSource, senderDomain, times + 1)
        }
    else {
      val message = s"MarkSentFailed: For $messageId max attempts reached with count $times"
      logger.error(s"$message")
      throw new RuntimeException(s"$message")
    }

  def decryptedTags(tags: Map[String, String]): Map[String, String] =
    tags.map { t =>
      Try(encryption.decrypt(t._2).value) match {
        case Success(i) => (t._1, i)
        case Failure(_) =>
          logger.warn(s"Error decrypting tags $t")
          (t._1, t._2)
      }
    }

  private def auditSend(response: ImiSendResponse, request: QueuedEmailRequest) = {
    val requestParams = request.parameters.filter(_._1 != "issueDate")
    auditConnector.sendEvent(
      DataEvent(
        auditSource = "email",
        auditType = EventTypes.Succeeded,
        tags = Map(EventKeys.TransactionName -> "Email Requested") ++ decryptedTags(request.tags),
        detail = (request.auditData -- requestParams.keys)
          ++ requestParams.map { case (key, value) => ("content_" + key, value) }
          ++ Map(
            "templateId"      -> request.templateId,
            "emailMessageId"  -> response.messageId,
            "correlationId"   -> response.correlationId,
            "senderDomain"    -> senderDomain,
            "to"              -> Json.toJson(request.to.map(_.value)).toString(),
            "templateVariant" -> "n/a"
          )
      )
    )
  }

}

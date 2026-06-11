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

package uk.gov.hmrc.email.services

import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.clusterworkthrottling.WorkThrottling
import uk.gov.hmrc.email.connectors._
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model._
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.utils.{ Encryption, NonEmptyString }
import uk.gov.hmrc.http.{ BadRequestException, HeaderCarrier }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import uk.gov.hmrc.play.audit.EventKeys
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Right, Success, Try }

@Singleton
class SendToMailgun @Inject() (
  senderDomain: String,
  encryption: Encryption,
  imiConfiguration: ImiConfiguration,
  emailRepository: EmailQueueRepository,
  mailgunConnector: MailgunConnector,
  auditConnector: AuditConnector,
  preSendingCheck: PreSendingCheckConnector,
  holdList: List[RecipientDomainPattern],
  allowList: List[RecipientDomainPattern],
  throttler: WorkThrottling
)(implicit ex: ExecutionContext)
    extends SendEmail(
      encryption: Encryption,
      imiConfiguration: ImiConfiguration,
      holdList: List[RecipientDomainPattern],
      allowList: List[RecipientDomainPattern]
    ) with Logging {

  def throttledSend(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest])(implicit
    hc: HeaderCarrier
  ): Future[EmailQueueProcessingResults] =
    throttler.throttledStartingFrom(pendingEmail.updatedAt) {
      pendingEmail.item.onSendUrl match {
        case Some(url) => checkAndSendEmail(url, current, pendingEmail)
        case None      => sendEmail(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest])
      }
    }

  private def sendEmail(current: EmailQueueProcessingResults, queueItem: WorkItem[QueuedEmailRequest]) = {
    val request = queueItem.item
    request.renderedEmail match {
      case Some(rendered) =>
        verifyAndSend(request, rendered, current, queueItem)
          .recoverWith { case e: BadRequestException =>
            badRequestHandler(request, e, current, queueItem)
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
    pendingEmail: WorkItem[QueuedEmailRequest]
  )(implicit hc: HeaderCarrier) =
    preSendingCheck
      .shouldISend(url)
      .flatMap {
        case Right(SendAlertResponse(true)) =>
          sendEmail(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest])
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

  private def badRequestHandler(
    request: QueuedEmailRequest,
    e: BadRequestException,
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest]
  ) =
    Future
      .sequence(request.to.map { address =>
        // NOTE: EmailAddress is correct by construction, ensuring that a non-empty string cannot be created
        mailgunConnector
          .validate(NonEmptyString.validate(address.value).toOption.get)
      })
      .flatMap { validations =>
        if (validations.contains(false))
          emailRepository
            .complete(pendingEmail.id)
            .map(_ => current.incrementPermanentlyFailed)
        else
          throw e
      }

  private def send(request: QueuedEmailRequest, renderResult: RenderResult): Future[MailgunSendResponse] =
    mailgunConnector.send(
      EmailMessage(
        from = renderResult.fromAddress,
        to = request.to,
        replyToAddress = request.replyToAddress,
        subject = renderResult.subject,
        plainTextBody = renderResult.plain,
        htmlBody = renderResult.html,
        templateId = request.templateId,
        templateRegime = renderResult.templateRegime,
        tags = request.tags
      )
    ) andThen { case Success(mailgunResponse) =>
      auditConnector.sendEvent(
        DataEvent(
          auditSource = "email",
          auditType = EventTypes.Succeeded,
          tags = Map(EventKeys.TransactionName -> "Email Sent") ++ decryptedTags(request.tags),
          detail = request.auditData
            ++ request.parameters.map { case (key, value) =>
              ("content_" + key, value)
            }
            ++ Map(
              "templateId"       -> request.templateId,
              "mailgunMessageId" -> mailgunResponse.id.toString,
              "senderDomain"     -> senderDomain,
              "to"               -> Json.toJson(request.to.map(_.value)).toString(),
              "templateVariant"  -> "n/a"
            )
        )
      )
    }

  def decryptedTags(tags: Map[String, String]) =
    tags.map { t =>
      Try(encryption.decrypt(t._2).value) match {
        case Success(i) => (t._1, i)
        case Failure(_) =>
          logger.warn(s"Error decrypting tags $t")
          (t._1, t._2)
      }
    }

  def deleteBounces(addresses: Seq[EmailAddress]): Future[Seq[Boolean]] =
    Future.sequence(addresses.map(mailgunConnector.deleteBouncesFor))

  def processForceFlag(forceFlag: Boolean, to: Seq[EmailAddress]): Future[Seq[Boolean]] = {
    val addresses = if (forceFlag) to else Seq.empty
    deleteBounces(addresses)
  }

  private def verifyAndSend(
    request: QueuedEmailRequest,
    renderResult: RenderResult,
    current: EmailQueueProcessingResults,
    pendingEmail: WorkItem[QueuedEmailRequest]
  ): Future[EmailQueueProcessingResults] = {
    val heldMatchingDomains = checkHoldList(request.to)
    val allowListedEmailAddresses = checkAllowList(request.to)
    val updatedRequest = request.copy(to = allowListedEmailAddresses)

    if (allowListedEmailAddresses.isEmpty) {
      logger.warn(s"Failed to sent email. ${request.to.mkString(",")} not allowListed. It will not be retried.")
      emailRepository.complete(pendingEmail.id).map(_ => current)
    } else if (heldMatchingDomains.nonEmpty)
      Future.failed(new RuntimeException(s"${heldMatchingDomains.mkString(",")} violated the hold list"))
    else
      for {
        _ <- processForceFlag(updatedRequest.force, updatedRequest.to)
        _ <- send(updatedRequest, renderResult)
        _ <- emailRepository.complete(pendingEmail.id)
      } yield current.incrementSent
  }

  def markAsFailed(current: EmailQueueProcessingResults, pendingEmail: WorkItem[QueuedEmailRequest]) =
    emailRepository
      .markAs(pendingEmail.id, ProcessingStatus.Failed)
      .map(_ => current.incrementRequeued)

}

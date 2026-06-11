/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, OFormat }

final case class EmailQueueProcessingResults(sent: Int, requeued: Int, permanentlyFailed: Int, aborted: Int) {
  def incrementSent: EmailQueueProcessingResults = copy(sent = sent + 1)

  def incrementRequeued: EmailQueueProcessingResults = copy(requeued = requeued + 1)

  def incrementPermanentlyFailed: EmailQueueProcessingResults =
    copy(permanentlyFailed = permanentlyFailed + 1)

  def incrementAborted: EmailQueueProcessingResults = copy(aborted = aborted + 1)

  def combine(that: EmailQueueProcessingResults): EmailQueueProcessingResults =
    copy(sent = sent + that.sent, requeued = requeued + that.requeued)
}

object EmailQueueProcessingResults {
  def empty: EmailQueueProcessingResults = EmailQueueProcessingResults(0, 0, 0, 0)

  implicit val formats: OFormat[EmailQueueProcessingResults] = Json.format[EmailQueueProcessingResults]
}

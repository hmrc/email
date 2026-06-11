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

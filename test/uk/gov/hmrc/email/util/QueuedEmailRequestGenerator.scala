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

package uk.gov.hmrc.email.util

import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.emailaddress.EmailAddress

trait QueuedEmailRequestGenerator {

  val toAddress: String = "a@b.com"
  val renderedResult: RenderResult =
    RenderResult("plaintext", "somehtml", "from@me.com", "OH HAI!", "generic", Some("templateId"))

  def generateAQueuedEmailRequest(
    to: List[EmailAddress] = List(EmailAddress(toAddress)),
    templateId: String = "template",
    parameters: Map[String, String] = Map.empty,
    force: Boolean = false,
    eventUrl: Option[String] = None,
    onSendUrl: Option[String] = None,
    auditData: Map[String, String] = Map.empty,
    renderedEmail: Option[RenderResult] = Some(renderedResult),
    replyToAddress: Option[EmailAddress] = None,
    tags: Map[String, String] = Map.empty
  ): QueuedEmailRequest =
    QueuedEmailRequest(
      to = to,
      templateId = templateId,
      parameters = parameters,
      tags = tags,
      force = force,
      eventUrl = eventUrl,
      onSendUrl = onSendUrl,
      auditData = auditData,
      renderedEmail = renderedEmail,
      replyToAddress = replyToAddress
    )

}

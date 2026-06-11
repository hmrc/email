/*
 * Copyright 2023 HM Revenue & Customs
 *
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

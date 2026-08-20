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

package uk.gov.hmrc.email.repositories.model

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.email.controllers.model.SendEmailRequest
import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.utils.Encryption
import uk.gov.hmrc.email.emailaddress.EmailAddress

case class QueuedEmailRequest(
  to: List[EmailAddress],
  templateId: String,
  parameters: Map[String, String],
  tags: Map[String, String] = Map.empty,
  force: Boolean,
  eventUrl: Option[String],
  onSendUrl: Option[String],
  auditData: Map[String, String],
  renderedEmail: Option[RenderResult],
  emailSource: Option[String] = None,
  replyToAddress: Option[EmailAddress] = None
)

object QueuedEmailRequest {

  implicit val rrf: OFormat[RenderResult] = Json.format[RenderResult]
  implicit val format: Format[QueuedEmailRequest] = new Format[QueuedEmailRequest] {

    import uk.gov.hmrc.email.emailaddress.PlayJsonFormats._

    def reads(json: JsValue): JsResult[QueuedEmailRequest] =
      (
        (__ \ "to").read[List[EmailAddress]] and
          (__ \ "templateId").read[String] and
          (__ \ "parameters")
            .readNullable[Map[String, String]]
            .map(_.getOrElse(Map.empty)) and
          (__ \ "tags")
            .readNullable[Map[String, String]]
            .map(_.getOrElse(Map.empty)) and
          (__ \ "force").readNullable[Boolean].map(_.getOrElse(false)) and
          (__ \ "eventUrl").readNullable[String] and
          (__ \ "onSendUrl").readNullable[String] and
          (__ \ "auditData")
            .readNullable[Map[String, String]]
            .map(_.getOrElse(Map.empty)) and
          (__ \ "renderedEmail").readNullable[RenderResult] and
          (__ \ "emailSource").readNullable[String] and
          (__ \ "replyToAddress").readNullable[EmailAddress]
      )(QueuedEmailRequest.apply).reads(json)

    def writes(o: QueuedEmailRequest): JsValue =
      Json.writes[QueuedEmailRequest].writes(o)
  }

  def from(
    sendEmailRequest: SendEmailRequest,
    renderResult: RenderResult,
    encryption: Encryption
  ): QueuedEmailRequest = {
    val tags = sendEmailRequest.enrolment.foldLeft(sendEmailRequest.tags) { (t, v) =>
      t + ("enrolment" -> v)
    } + ("templateId" -> renderResult.templateId.getOrElse(sendEmailRequest.templateId))
    QueuedEmailRequest(
      to = sendEmailRequest.to,
      templateId = renderResult.templateId.getOrElse(sendEmailRequest.templateId),
      parameters = sendEmailRequest.parameters,
      tags = tags.view.mapValues(encryption.encrypt(_).value).toMap,
      force = sendEmailRequest.force,
      eventUrl = sendEmailRequest.eventUrl,
      onSendUrl = sendEmailRequest.onSendUrl,
      auditData = sendEmailRequest.auditData,
      renderedEmail = Some(renderResult),
      emailSource = sendEmailRequest.emailSource,
      replyToAddress = sendEmailRequest.replyToAddress
    )
  }
}

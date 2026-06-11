/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import play.api.libs.json._

case class TemplateRenderRequest(parameters: Map[String, String], email: Option[String])

object TemplateRenderRequest {
  implicit val templateRenderRequestFormat: OFormat[TemplateRenderRequest] = Json.format[TemplateRenderRequest]
}

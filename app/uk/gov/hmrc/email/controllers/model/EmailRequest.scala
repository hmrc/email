/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.controllers.model

import play.api.libs.json._

case class EmailRequest(email: String)

object EmailRequest {
  implicit val formats: OFormat[EmailRequest] = Json.format[EmailRequest]
}

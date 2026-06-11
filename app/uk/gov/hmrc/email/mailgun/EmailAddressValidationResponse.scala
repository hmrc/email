/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.mailgun

import play.api.libs.json.{ Json, OFormat }

case class EmailAddressValidationResponse(is_valid: Boolean)

object EmailAddressValidationResponse {
  implicit val emailFormat: OFormat[EmailAddressValidationResponse] = Json.format[EmailAddressValidationResponse]
}

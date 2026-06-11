/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.emailaddress

object PlayJsonFormats {
  import play.api.libs.json._

  implicit val emailAddressReads: Reads[EmailAddress] = new Reads[EmailAddress] {
    def reads(js: JsValue): JsResult[EmailAddress] =
      js.validate[String].flatMap {
        case s if EmailAddress.isValid(s) => JsSuccess(EmailAddress(s))
        case _                            => JsError("not a valid email address")
      }
  }
  implicit val emailAddressWrites: Writes[EmailAddress] = new Writes[EmailAddress] {
    def writes(e: EmailAddress): JsValue = JsString(e.value)
  }
}

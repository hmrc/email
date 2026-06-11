/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, OWrites }

case class ImiConsent(channel: String, address: String, consent: Boolean, reason: String)

object ImiConsent {
  implicit val formatWrites: OWrites[ImiConsent] = Json.writes[ImiConsent]
}

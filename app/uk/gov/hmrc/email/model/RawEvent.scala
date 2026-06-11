/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, OFormat }

case class RawEvent(deliveryInfoNotification: DeliveryInfoNotification)

object RawEvent {
  implicit val reads: OFormat[RawEvent] = Json.format[RawEvent]
}

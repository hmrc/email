/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._
import java.util.{ Base64, UUID }

final case class DeliveryInfoNotification(
  deliveryInfo: DeliveryInfo,
  subtId: String,
  transId: UUID,
  callbackData: Map[String, String],
  correlationId: UUID
)

object DeliveryInfoNotification {

  private def decodeString(s: String) = {
    val decodedBytes = Base64.getDecoder.decode(s)
    val decodedString = new String(decodedBytes)
    Json.parse(decodedString).as[Map[String, String]]
  }

  implicit val formatReads: Reads[DeliveryInfoNotification] = (
    (__ \ "deliveryInfo").read[DeliveryInfo] and
      (__ \ "subtid").read[String] and
      (__ \ "transid").read[UUID] and
      (__ \ "callbackData").read[String].map(decodeString) and
      (__ \ "correlationid").read[UUID]
  )(DeliveryInfoNotification.apply)

  implicit val formatWrites: OWrites[DeliveryInfoNotification] = Json.writes[DeliveryInfoNotification]

}

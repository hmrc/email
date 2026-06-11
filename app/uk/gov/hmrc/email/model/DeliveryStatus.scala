/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Format, JsResult, JsString, JsSuccess, JsValue }

enum DeliveryStatus {
  case Submitted
  case Read
  case Delivered
  case Bounce
  case Failed
  case Complained
  case UnInterested
}

object DeliveryStatus {
  implicit val format: Format[DeliveryStatus] = new Format[DeliveryStatus] {
    override def reads(json: JsValue): JsResult[DeliveryStatus] =
      json.validate[String].map(_.toLowerCase).flatMap {
        case "submitted"    => JsSuccess(Submitted)
        case "read"         => JsSuccess(Read)
        case "delivered"    => JsSuccess(Delivered)
        case "bounce"       => JsSuccess(Bounce)
        case "failed"       => JsSuccess(Failed)
        case "complained"   => JsSuccess(Complained)
        case "uninterested" => JsSuccess(UnInterested)
      }

    override def writes(o: DeliveryStatus): JsValue = JsString(o.toString)
  }

  def withNameInsensitiveOption(name: String): Option[DeliveryStatus] =
    DeliveryStatus.values.find(_.toString.equalsIgnoreCase(name))
}

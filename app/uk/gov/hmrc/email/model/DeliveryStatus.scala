/*
 * Copyright 2023 HM Revenue & Customs
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

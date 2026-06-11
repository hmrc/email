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

package uk.gov.hmrc.email.controllers.model

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.email.model.DeliveryStatus
import java.time.LocalDateTime
import java.util.UUID

case class Event(
  messageId: UUID,
  correlationId: UUID,
  status: DeliveryStatus,
  timeStamp: LocalDateTime,
  code: String,
  description: String,
  additionalInfo: String,
  emailAddress: String,
  tags: Map[String, String]
)

object Event {
  implicit val formatReads: Reads[Event] = ((__ \ "messageId").read[UUID] and
    (__ \ "correlationId").read[UUID] and
    (__ \ "status").read[DeliveryStatus] and
    (__ \ "timeStamp").read[LocalDateTime] and
    (__ \ "code").read[String] and
    (__ \ "description").read[String] and
    (__ \ "additionalInfo").read[String] and
    (__ \ "emailAddress").read[String] and
    (__ \ "tags").read[Map[String, String]])(Event.apply)
}

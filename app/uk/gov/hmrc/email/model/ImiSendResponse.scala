/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, Reads }

final case class ImiSendResponse(requestTimestamp: String, messageId: String, correlationId: String, status: String)

object ImiSendResponse {
  implicit val format: Reads[ImiSendResponse] = Json.reads[ImiSendResponse]
}

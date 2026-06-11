/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories.model

import java.time.Instant
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

final case class EmailStats(name: String, count: Long, createdAt: Instant)

object EmailStats {
  import play.api.libs.json._

  implicit val dateFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
  implicit val format: Format[EmailStats] = Json.format[EmailStats]
}

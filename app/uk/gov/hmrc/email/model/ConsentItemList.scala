/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.{ Json, Reads }

final case class ConsentItemList(items: List[ConsentItem], continueToken: Option[String])

object ConsentItemList {
  implicit val reads: Reads[ConsentItemList] = Json.reads[ConsentItemList]
}

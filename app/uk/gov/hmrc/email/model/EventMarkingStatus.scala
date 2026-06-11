/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

sealed trait EventMarkingStatus
object EventMarkingStatus {
  case object Marked extends EventMarkingStatus
  case object AlreadyMarkedOrNotFound extends EventMarkingStatus
}

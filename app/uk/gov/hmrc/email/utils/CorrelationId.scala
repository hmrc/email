/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.utils

import java.util.UUID
case class CorrelationId(value: UUID)

object CorrelationId {
  def apply(): CorrelationId = CorrelationId(UUID.randomUUID())

}

/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

sealed trait MetricPrefix {
  def name: String
}

object MetricPrefix {
  case object FormId extends MetricPrefix {
    val name = "formId"
  }
  case object Domain extends MetricPrefix {
    val name = "domain"
  }
}

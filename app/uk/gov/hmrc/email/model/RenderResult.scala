/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

case class RenderResult(
  plain: String,
  html: String,
  fromAddress: String,
  subject: String,
  templateRegime: String,
  templateId: Option[String]
)

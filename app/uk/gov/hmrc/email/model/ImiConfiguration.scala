/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

final case class ImiConfiguration(
  useImiConnector: Boolean,
  imiGroupId: String,
  notifyUrl: String
)

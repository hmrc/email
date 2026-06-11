/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import uk.gov.hmrc.email.emailaddress.EmailAddress

case class EmailMessage(
  from: String,
  to: List[EmailAddress],
  replyToAddress: Option[EmailAddress],
  subject: String,
  plainTextBody: String,
  htmlBody: String,
  templateId: String,
  templateRegime: String,
  tags: Map[String, String] = Map.empty[String, String]
)

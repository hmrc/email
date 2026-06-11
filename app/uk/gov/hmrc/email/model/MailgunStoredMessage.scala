/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

final case class MailgunStoredMessage(enrolment: Option[String], messageId: String)

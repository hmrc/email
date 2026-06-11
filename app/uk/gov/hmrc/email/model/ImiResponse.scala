/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

sealed trait DeleteConsentResponse

case object DeleteConsentSuccess extends DeleteConsentResponse
case object DeleteConsentNotFound extends DeleteConsentResponse
case class DeleteConsentFailed(statusCode: Option[Int], message: String) extends DeleteConsentResponse

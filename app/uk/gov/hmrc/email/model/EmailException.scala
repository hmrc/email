/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

sealed trait EmailException extends Exception {
  val message: String
}

final case class ReplyToAddress(message: String) extends EmailException
final case class RendererEmptyException(message: String) extends EmailException

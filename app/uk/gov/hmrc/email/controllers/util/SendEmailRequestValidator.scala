/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.controllers.util

import play.api.libs.json.Reads

object SendEmailRequestValidator {

  def jsonAlertQueueReads: Reads[String] =
    Reads[String] { alertQueue =>
      alertQueue.validate[String].map[String] { queueString =>
        if (queueString.trim.isEmpty)
          throw ValidationException(s"alertQueue: invalid alert queue provided")
        else if (!alertQueueTypes.contains(queueString))
          throw ValidationException(s"alertQueue: invalid alert queue provided")
        else queueString
      }
    }

  def checkValidAlertQueue(alertQueue: Option[String]): Boolean =
    alertQueue match {
      case Some(alertQueue) if alertQueueTypes.contains(alertQueue) => true
      case _                                                        => false
    }

  def checkEmptyAlertQueue(alertQueue: Option[String]): Boolean =
    alertQueue match {
      case Some(alertQueue) if alertQueue.trim.isEmpty => true
      case _                                           => false
    }

  val alertQueueTypes: List[String] = List("PRIORITY", "DEFAULT", "BACKGROUND")

}

case class ValidationException(message: String) extends RuntimeException(message)

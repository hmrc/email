/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

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

package uk.gov.hmrc.email.services

import play.api.Logging
import uk.gov.hmrc.email.connectors._
import uk.gov.hmrc.email.repositories._
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }

class Queue @Inject() (emailRepository: EmailQueueRepository)(implicit ec: ExecutionContext) extends Logging {
  def add(request: QueuedEmailRequest, isImi: Boolean): Future[Either[ErrorMessage, Unit]] =
    if (isImi)
      Future
        .sequence(request.to.map { email =>
          emailRepository.enqueue(request.copy(to = List(email))).map(_ => Right((): Unit)).recover { case e =>
            logger.error(s"Failed to queue due to ${e.getMessage}")
            Left(ErrorMessage(s"Failed to queue due to ${e.getMessage}"))
          }
        })
        .map { results =>
          val errors = results.collect { case Left(error) => error }
          if (errors.nonEmpty) Left(errors.head)
          else Right(())
        }
    else
      emailRepository.enqueue(request).map(_ => Right((): Unit)).recover { case e =>
        logger.error(s"Failed to queue due to ${e.getMessage}")
        Left(ErrorMessage(s"Failed to queue due to ${e.getMessage}"))
      }

}

/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import play.api.inject.ApplicationLifecycle
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.config.ScheduledJobConfig
import uk.gov.hmrc.email.{ TimedLog, Warning }
import uk.gov.hmrc.email.model.EmailQueueProcessingResults
import uk.gov.hmrc.email.services.Outbox
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ ExecutionContext, Future }

class SendEmailJob(
  val name: String,
  val outbox: Outbox,
  val configuration: Configuration,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit actorSystem: ActorSystem) {

  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  private val config = ScheduledJobConfig(configuration, name)
  private val logger: Logger = Logger(getClass)

  private val formattedName = s"SendEmailJob-$name"

  ScheduledStream
    .builder(config, name, sink, logger, Some(lifecycle))(actorSystem)
    .withWorkload {
      TimedLog(logger, formattedName, Warning) {
        outbox
          .sendAll(HeaderCarrier())
          .map { case EmailQueueProcessingResults(sent, requeued, permanentlyFailed, aborted) =>
            logger.debug(
              s"$formattedName $sent sent $requeued requeued $permanentlyFailed permanentlyFailed $aborted aborted sending"
            )
          }
          .recoverWith { case e: Throwable =>
            logger.error(s"$formattedName - an error occurred: $e")
            Future.successful(())
          }
      }
    }
    .build()
}

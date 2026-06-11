/*
 * Copyright 2023 HM Revenue & Customs
 *
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

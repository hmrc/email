/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

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
import uk.gov.hmrc.email.services.SftpProcessing

import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext

@Singleton
class SftpEventProcessingJob @Inject() (
  conf: Configuration,
  sftpProcessing: SftpProcessing,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit actorSystem: ActorSystem) {
  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  private val name: String = "event-processing-sftp"
  private val config = ScheduledJobConfig(conf, name)
  private val logger: Logger = Logger(getClass)

  ScheduledStream
    .builder(config, name, sink, logger, Some(lifecycle))(actorSystem)
    .withWorkload {
      TimedLog(logger, "SftpEventProcessingJob", Warning) {
        sftpProcessing.processFiles.map(_ => Result("SftpEventProcessingJob complete"))
      }
    }
    .build()
}

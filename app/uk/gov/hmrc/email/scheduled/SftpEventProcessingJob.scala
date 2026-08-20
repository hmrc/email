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

/*
 * Copyright 2026 HM Revenue & Customs
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

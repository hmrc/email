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

import javax.inject.{ Inject, Singleton }
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.connectors.{ EventEmitter, EventEmitterResults }
import uk.gov.hmrc.email.repositories.EmailEventsRepository
import uk.gov.hmrc.http.client.HttpClientV2
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.email.config.ScheduledJobConfig
import uk.gov.hmrc.email.{ TimedLog, Warning }

import scala.concurrent.{ ExecutionContext, Future }

case class EventEmitterJob(
  senderDomain: String,
  httpClient: HttpClientV2,
  config: EventEmitterConfig,
  emailEventsRepository: EmailEventsRepository,
  configuration: Configuration,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit materializer: Materializer, actorSystem: ActorSystem) {
  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  val configKey: String = "event-emitter"
  val name = s"$senderDomain-$configKey"
  private val scheduledJobConfig = ScheduledJobConfig(configuration, configKey)
  private val logger: Logger = Logger(getClass)

  ScheduledStream
    .builder(scheduledJobConfig, name, sink, logger, Some(lifecycle))(actorSystem)
    .withWorkload {
      logger.debug(s"EventEmitterJob $name for $senderDomain starting")
      TimedLog(logger, name, Warning) {
        new EventEmitter(httpClient, config, emailEventsRepository).emitEvents
          .map { case EventEmitterResults(emitted, failed) =>
            logger.warn(s"$name Emitted $emitted, failed $failed")
          }
          .recoverWith { case e: Throwable =>
            logger.error(s"$name EventEmitterJob - an error occurred: $e")
            Future.successful(())
          }
      }
    }
    .build()
}

case class EventEmitterConfig(regex: String, replaceString: String)

@Singleton
class EventEmitterJobFactory @Inject() (
  httpClient: HttpClientV2,
  emailEventsRepository: EmailEventsRepository,
  configuration: Configuration,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit materializer: Materializer, actorSystem: ActorSystem) {
  private val eventEmitterConfig: EventEmitterConfig =
    EventEmitterConfig(
      configuration.get[String]("eventUrlMapping.regex"),
      configuration.get[String]("eventUrlMapping.replaceString")
    )

  def apply(senderDomain: String): EventEmitterJob =
    EventEmitterJob(senderDomain, httpClient, eventEmitterConfig, emailEventsRepository, configuration, lifecycle, sink)
}

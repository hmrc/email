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

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import play.api.Mode.Dev
import play.api.inject.ApplicationLifecycle

import javax.inject.{ Inject, Singleton }
import play.api.{ Configuration, Environment, Logger }
import uk.gov.hmrc.clusterworkthrottling.ServiceInstances
import uk.gov.hmrc.email.config.ScheduledJobConfig

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class InstanceCounterJob @Inject() (
  conf: Configuration,
  environment: Environment,
  serviceInstances: ServiceInstances,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit actorSystem: ActorSystem) {
  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  private val name: String = "serviceInstanceCounter"
  private val scheduledJobConfig = ScheduledJobConfig(conf, name)
  private val logger: Logger = Logger(getClass)

  // Track the current running workload
  private val currentWorkload = new AtomicReference[Option[Future[Unit]]](None)

  val stream: ScheduledStream = ScheduledStream
    .builder(scheduledJobConfig, name, sink, logger, Some(lifecycle))(actorSystem)
    .withWorkload {
      logger.debug(s"Running $name")
      val workloadFuture = for {
        instances <- serviceInstances.heartbeat()
      } yield {
        logger.warn(s"Now running $instances instance(s)")
        ()
      }

      // Store the future
      currentWorkload.set(Some(workloadFuture))

      // Clear when complete
      workloadFuture.andThen { case _ =>
        currentWorkload.set(None)
      }
    }
    .build()

  // Needed for live-reload during Dev preview for email templates
  def isRunning: Future[Boolean] =
    Future.successful(
      if (environment.mode == Dev)
        false
      else
        currentWorkload.get().exists(!_.isCompleted)
    )
}

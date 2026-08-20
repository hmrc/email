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
import uk.gov.hmrc.email.services.SuppressionListManagement
import uk.gov.hmrc.email.{ TimedLog, Warning }
import uk.gov.hmrc.mongo.lock.{ LockService, MongoLockRepository }

import javax.inject.{ Inject, Singleton }
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext

@Singleton
class CleanSuppressionListJob @Inject() (
  val configuration: Configuration,
  lockRepository: MongoLockRepository,
  suppressionListManagement: SuppressionListManagement,
  lifecycle: ApplicationLifecycle,
  sink: Sink[Unit, ?] = Sink.ignore
)(implicit actorSystem: ActorSystem) {
  private val name: String = "clean-suppression-list"

  private implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val config = ScheduledJobConfig(configuration, name)
  private val logger: Logger = Logger(getClass)

  private val domainsOfInterest: List[String] =
    configuration.get[Seq[String]]("suppression-list-delete-domain-list").toList

  val stream: ScheduledStream = ScheduledStream
    .builder(config, name, sink, logger, Some(lifecycle))(actorSystem)
    .withWorkload {
      TimedLog(logger, "CleanSuppressionListJob", Warning) {
        logger.warn("CleanSuppressionListJob Start")
        for {
          _ <- suppressionListManagement.clean(domainsOfInterest)
          _ = logger.debug(s"CleanSuppressionListJob")
        } yield ()
      }
    }
    .withLocking {
      LockService(lockRepository, lockId = name, 1.hour)
    }
    .build()
}

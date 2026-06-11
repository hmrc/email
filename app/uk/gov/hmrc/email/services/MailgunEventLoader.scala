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

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.LockRepository
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait TimePeriodLockService {

  val lockRepository: LockRepository
  val lockId: String
  val ttl: Duration

  private val ownerId = UUID.randomUUID().toString

  def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
      refreshed <- lockRepository.refreshExpiry(lockId, ownerId, ttl)
      acquired <- if (!refreshed) lockRepository.takeLock(lockId, ownerId, ttl)
                  else Future.successful(None)

      result <- if (refreshed || acquired.nonEmpty)
                  body.map(Option.apply)
                else
                  Future.successful(None)
    } yield result).recoverWith { case ex =>
      lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }

  def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
      acquired <- lockRepository.takeLock(lockId, ownerId, ttl)
      result <- if (acquired.nonEmpty)
                  body.flatMap(value => lockRepository.releaseLock(lockId, ownerId).map(_ => Some(value)))
                else
                  Future.successful(None)
    } yield result).recoverWith { case ex =>
      lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }

  def isLocked: Future[Boolean] = lockRepository.isLocked(lockId, ownerId)
}

final case class MailgunLock(configuration: Configuration, lockKeeperId: String, lockRepo: LockRepository)
    extends TimePeriodLockService {

  override val lockRepository = lockRepo

  override val lockId: String = lockKeeperId

  override val ttl: Duration =
    configuration
      .getOptional[Duration]("lock.forceReleaseAfter")
      .getOrElse(throw new IllegalStateException("lock.forceReleaseAfter config value not set"))
}

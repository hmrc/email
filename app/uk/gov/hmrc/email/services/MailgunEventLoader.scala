/*
 * Copyright 2023 HM Revenue & Customs
 *
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

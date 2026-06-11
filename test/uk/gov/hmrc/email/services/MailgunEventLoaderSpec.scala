/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.test.Helpers.*
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ EMPTY_STRING, TEST_ID, TEST_TIME_INSTANT }
import uk.gov.hmrc.mongo.lock.{ Lock, LockRepository }
import org.mockito.ArgumentMatchers.any

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration.{ Duration, MINUTES }

class MailgunEventLoaderSpec extends SpecBase {

  "TimePeriodLockService.ttl" should {
    "return correct duration" in new Setup {
      mailgunLockWithConfigWithProperty.ttl mustBe Duration(1, MINUTES)
    }

    "throw exception when property is not found in the configuration" in new Setup {
      intercept[IllegalStateException] {
        mailgunLockWithConfigWithoutLockProperty.ttl
      }.getMessage must be("lock.forceReleaseAfter config value not set")
    }
  }

  "TimePeriodLockService.withRenewedLock" should {
    "return body when refreshExpiry is successful" in new Setup {
      when(lockRepo.refreshExpiry(any, any, any)).thenReturn(Future.successful(true))

      val result: Option[String] =
        await(mailgunLockWithConfigWithProperty.withRenewedLock(bodyToFutureFunction(Some("test"))))

      result must be(Some("test"))
    }

    "return None when refreshExpiry and lock acquisition both are unsuccessful" in new Setup {
      when(lockRepo.refreshExpiry(any, any, any)).thenReturn(Future.successful(false))
      when(lockRepo.takeLock(any, any, any)).thenReturn(Future.successful(None))

      when(lockRepo.releaseLock(any, any)).thenReturn(Future.successful(()))

      val result: Option[String] =
        await(mailgunLockWithConfigWithProperty.withRenewedLock(bodyToFutureFunction(Some("test"))))

      result mustBe empty
    }

    "throw exception when error occurred while acquiring the lock" in new Setup {
      when(lockRepo.refreshExpiry(any, any, any)).thenReturn(Future.successful(false))
      when(lockRepo.takeLock(any, any, any)).thenReturn(Future.failed(RuntimeException("Error occurred")))
      when(lockRepo.releaseLock(any, any)).thenReturn(Future.successful(()))

      intercept[RuntimeException] {
        await(mailgunLockWithConfigWithProperty.withRenewedLock(bodyToFutureFunction(Some("test"))))
      }
    }
  }

  "TimePeriodLockService.withLock" should {
    "return None when lock is not acquired" in new Setup {
      when(lockRepo.takeLock(any, any, any)).thenReturn(Future.successful(None))

      val result: Option[String] = await(mailgunLockWithConfigWithProperty.withLock(bodyToFutureFunction(Some("test"))))

      result mustBe empty
    }

    "return body when lock is acquired successfully" in new Setup {
      when(lockRepo.takeLock(any, any, any)).thenReturn(
        Future.successful(
          Some(
            Lock(
              id = TEST_ID,
              owner = "test_system",
              timeCreated = TEST_TIME_INSTANT,
              expiryTime = TEST_TIME_INSTANT
            )
          )
        )
      )

      when(lockRepo.releaseLock(any, any)).thenReturn(Future.successful(()))

      val result: Option[String] = await(mailgunLockWithConfigWithProperty.withLock(bodyToFutureFunction(Some("test"))))

      result must be(Some("test"))
    }

    "throw exception when error occurred while acquiring the lock" in new Setup {
      when(lockRepo.takeLock(any, any, any)).thenReturn(Future.failed(RuntimeException("Error occurred")))
      when(lockRepo.releaseLock(any, any)).thenReturn(Future.successful(()))

      intercept[RuntimeException] {
        await(mailgunLockWithConfigWithProperty.withLock(bodyToFutureFunction(Some("test"))))
      }
    }
  }

  "TimePeriodLockService.isLocked" should {
    "true when lock is acquired" in new Setup {
      when(lockRepo.isLocked(any, any)).thenReturn(Future.successful(true))

      val result: Boolean = await(mailgunLockWithConfigWithProperty.isLocked)

      result must be(true)
    }
  }

  trait Setup {
    val lockKeeperId = "test_id"
    val lockRepo: LockRepository = mock[LockRepository]

    implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

    lazy val configWithProperty: Configuration = Configuration(
      "lock.forceReleaseAfter" -> "1minute"
    )

    lazy val configWithoutLockProperty: Configuration = Configuration(
      "scheduling.serviceInstanceCounter.interval" -> "1minute"
    )

    lazy val mailgunLockWithConfigWithProperty: MailgunLock =
      MailgunLock(configuration = configWithProperty, lockKeeperId = lockKeeperId, lockRepo = lockRepo)

    lazy val mailgunLockWithConfigWithoutLockProperty: MailgunLock =
      MailgunLock(configuration = configWithoutLockProperty, lockKeeperId = lockKeeperId, lockRepo = lockRepo)

    def bodyToFutureFunction(body: Option[String]): Future[String] =
      Future(body.getOrElse(EMPTY_STRING))
  }
}

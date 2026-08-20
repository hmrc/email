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
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.testkit.{ ImplicitSender, TestKit }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.{ Configuration, Logger }
import uk.gov.hmrc.email.config.ScheduledJobConfig
import uk.gov.hmrc.mongo.lock.{ LockRepository, LockService }

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt

class ScheduledStreamSpec
    extends TestKit(ActorSystem("ScheduledStreamSpec")) with ImplicitSender with AnyWordSpecLike with Matchers
    with BeforeAndAfterAll with ScalaFutures {

  implicit val ec: ExecutionContext = system.dispatcher
  val logger = Logger(getClass)

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  // Helper to create config
  def testConfig(
    jobName: String = "job",
    enabled: Boolean = true,
    initialDelay: String = "0 milliseconds",
    interval: String = "100 milliseconds"
  ): ScheduledJobConfig = {
    val configuration = Configuration(
      s"scheduling.$jobName.initialDelay" -> initialDelay,
      s"scheduling.$jobName.interval"     -> interval,
      s"scheduling.$jobName.timeout"      -> "100"
    )
    ScheduledJobConfig(configuration, jobName)
  }

  "Scheduled stream" should {

    "execute workload on each tick" in new Setup {
      var executionCount = 0

      val stream = ScheduledStream
        .builder(
          config = testConfig(interval = "100 milliseconds"),
          name = "test-stream",
          sink = sink, // Pass the materialized sink
          logger = logger
        )
        .withWorkload {
          executionCount += 1
          Future.successful(())
        }
        .build()

      // Now use the probe to test
      testProbe.request(1)
      testProbe.expectNext(500.millis, ())
      executionCount shouldBe 1

      testProbe.request(1)
      testProbe.expectNext(500.millis, ())
      executionCount shouldBe 2

      stream.stop()
      testProbe.cancel()
    }

    "skip execution when conditional returns false" in new Setup {
      var executionCount = 0
      var conditionalFlag = false

      val stream = ScheduledStream
        .builder(
          config = testConfig(interval = "50 milliseconds"),
          name = "conditional-stream",
          sink = sink,
          logger = logger
        )
        .withWorkload {
          executionCount += 1
          Future.successful(())
        }
        .withConditional {
          conditionalFlag
        }
        .build()

      // First tick - conditional is false, should complete but not execute workload
      testProbe.request(1)
      testProbe.expectNext(200.millis, ())
      executionCount shouldBe 0

      // Set conditional to true
      conditionalFlag = true

      // Second tick - should execute
      testProbe.request(1)
      testProbe.expectNext(200.millis, ())
      executionCount shouldBe 1

      stream.stop()
      testProbe.cancel()
    }

    "execute with lock when configured" in new Setup {
      var lockAcquired = false
      var workloadExecuted = false

      val mockLock = mock[LockRepository]

      val mockLockService = new LockService {
        val lockId: String = "lock-id"
        val lockRepository: uk.gov.hmrc.mongo.lock.LockRepository = mockLock
        val ttl: scala.concurrent.duration.Duration = 1.minute

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] = {
          lockAcquired = true
          body.map(Some(_))
        }
      }

      val stream = ScheduledStream
        .builder(
          config = testConfig(interval = "50 milliseconds"),
          name = "locked-stream",
          sink = sink,
          logger = logger
        )
        .withWorkload {
          workloadExecuted = true
          Future.successful(())
        }
        .withLocking(mockLockService)
        .build()

      testProbe.request(1)
      testProbe.expectNext(200.millis, ())

      lockAcquired shouldBe true
      workloadExecuted shouldBe true

      stream.stop()
      testProbe.cancel()
    }

    "skip execution when lock cannot be acquired" in new Setup {
      var workloadExecuted = false

      val mockLock = mock[LockRepository]

      val failingLockService = new LockService {
        val lockId: String = "lock-id"
        val lockRepository: uk.gov.hmrc.mongo.lock.LockRepository = mockLock
        val ttl: scala.concurrent.duration.Duration = 1.minute

        override def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
          Future.successful(None) // Simulate lock failure
      }

      val stream = ScheduledStream
        .builder(
          config = testConfig(interval = "50 milliseconds"),
          name = "failed-lock-stream",
          sink = sink,
          logger = logger
        )
        .withWorkload {
          workloadExecuted = true
          Future.successful(())
        }
        .withLocking(failingLockService)
        .build()

      testProbe.request(1)
      testProbe.expectNext(200.millis, ())

      workloadExecuted shouldBe false // Should not execute

      stream.stop()
      testProbe.cancel()
    }

    "recover from workload failures" in new Setup {
      var executionCount = 0

      val stream = ScheduledStream
        .builder(
          config = testConfig(interval = "50 milliseconds"),
          name = "failing-stream",
          sink = sink,
          logger = logger
        )
        .withWorkload {
          executionCount += 1
          if (executionCount == 1) {
            Future.failed(new RuntimeException("Test failure"))
          } else {
            Future.successful(())
          }
        }
        .build()

      // First tick - fails but recovers
      testProbe.request(1)
      testProbe.expectNext(200.millis, ())
      executionCount shouldBe 1

      // Second tick - succeeds
      testProbe.request(1)
      testProbe.expectNext(200.millis, ())
      executionCount shouldBe 2

      stream.stop()
      testProbe.cancel()
    }

    import scala.jdk.CollectionConverters._
    "respect configured intervals" in new Setup {
      val executionTimes = new ConcurrentLinkedQueue[Long]()
      val startTime = System.currentTimeMillis()

      val stream = ScheduledStream
        .builder(
          config = testConfig(
            initialDelay = "100.millis",
            interval = "100.millis"
          ),
          name = "timed-stream",
          sink = sink,
          logger = logger
        )
        .withWorkload {
          executionTimes.add(System.currentTimeMillis() - startTime)
          Future.successful(())
        }
        .build()

      // Request and wait for 3 ticks
      testProbe.request(3)
      testProbe.expectNext(200.millis) // First tick at ~100ms
      testProbe.expectNext(200.millis) // Second tick at ~200ms
      testProbe.expectNext(200.millis) // Third tick at ~300ms

      val times = executionTimes.asScala.toList
      times.size shouldBe 3

      // Verify initial delay
      times.head should be(100L +- 50L)

      // Verify intervals between executions
      val intervals = times
        .sliding(2)
        .map {
          case List(a, b) => b - a
          case _          => 0L
        }
        .toList
      intervals.foreach { interval =>
        interval should be(100L +- 50L) // Should be ~100ms between executions
      }

      stream.stop()
    }
  }

  trait Setup {
    // Create the test sink
    val (testProbe, sink) = TestSink
      .probe[Unit](system)
      .preMaterialize() // This gives us both the probe and the sink
  }

}

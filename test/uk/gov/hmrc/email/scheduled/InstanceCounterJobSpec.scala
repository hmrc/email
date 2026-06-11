/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.mockito.Mockito.{ times, verify, when }
import org.mockito.ArgumentMatchers.any
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.Mode.{ Dev, Test }
import play.api.{ Configuration, Environment }
import play.api.inject.ApplicationLifecycle
import play.api.test.Helpers.*
import uk.gov.hmrc.clusterworkthrottling.ServiceInstances

import scala.concurrent.{ ExecutionContext, Future }

class InstanceCounterJobSpec extends PlaySpec {
  val testKit: ActorTestKit = ActorTestKit()
  implicit val system: ActorSystem = testKit.system.classicSystem
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: Materializer = Materializer(system)

  "Instance counter job" should {

    "emits elements correctly" in new Setup {
      when(mockService.heartbeat(any)(any)).thenReturn(Future.successful(1))

      probeSubscriber.request(2)
      probeSubscriber.expectNext(())
      probeSubscriber.expectNext(())
      scheduledJob.stream.stop()
      verify(mockService, times(2)).heartbeat()
    }

    "respect configured delays and intervals" in new Setup {
      when(mockService.heartbeat(any)(any)).thenReturn(Future.successful(1))

      val startTime: Long = System.currentTimeMillis()

      probeSubscriber
        .request(2)
        .expectNext(()) // Should arrive after ~100ms

      val firstElementTime: Long = System.currentTimeMillis()
      (firstElementTime - startTime) must be >= 100L

      probeSubscriber
        .expectNext(()) // Should arrive after another ~200ms

      val secondElementTime: Long = System.currentTimeMillis()
      (secondElementTime - firstElementTime) must be >= 180L // Give it some leeway
      verify(mockService, times(2)).heartbeat()
    }

    "track the current running workload" when {
      "environment is Dev" in new Setup {
        await(scheduledJob.isRunning) must be(false)
      }

      "environment is other than Dev" in new Setup {
        when(environment.mode).thenReturn(Test)

        await(scheduledJob.isRunning) must be(false)
      }
    }
  }

  trait Setup {
    val jobName = "serviceInstanceCounter"

    val configuration: Configuration = Configuration(
      s"scheduling.$jobName.initialDelay" -> "100 milliseconds",
      s"scheduling.$jobName.interval"     -> "200 milliseconds",
      s"scheduling.$jobName.timeout"      -> "100"
    )
    val mockService: ServiceInstances = mock[ServiceInstances]
    val lifecycle: ApplicationLifecycle = mock[ApplicationLifecycle]
    val environment: Environment = mock[Environment]

    when(environment.mode).thenReturn(Dev)

    val (probeSubscriber, probeSink) = TestSink.probe[Unit].preMaterialize()

    val scheduledJob =
      InstanceCounterJob(
        configuration,
        environment,
        mockService,
        lifecycle,
        sink = probeSink
      )
  }
}

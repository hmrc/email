/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.email.services.Outbox

import scala.concurrent.{ ExecutionContext, Future }

class SendEmailJobSpec extends PlaySpec {
  val testKit = ActorTestKit()
  implicit val system: ActorSystem = testKit.system.classicSystem
  implicit val ec: ExecutionContext = system.dispatcher
  implicit lazy val materializer: Materializer = Materializer(system)

  "Send email job" should {

    "emits elements correctly" in new Setup {
      when(mockService.sendAll(any)).thenReturn(Future.successful(()))

      probeSubscriber
        .request(2)
        .expectNext(())
        .expectNext(())

      verify(mockService, times(2)).sendAll(any)
    }

    "respect configured delays and intervals" in new Setup {
      when(mockService.sendAll(any)).thenReturn(Future.successful(()))

      val startTime = System.currentTimeMillis()

      probeSubscriber
        .request(2)
        .expectNext(()) // Should arrive after ~100ms

      val firstElementTime = System.currentTimeMillis()
      (firstElementTime - startTime) must be >= 100L

      probeSubscriber
        .expectNext(()) // Should arrive after another ~200ms

      val secondElementTime = System.currentTimeMillis()
      (secondElementTime - firstElementTime) must be >= 180L // Give it some leeway
      verify(mockService, times(2)).sendAll(any)
    }

  }

  trait Setup {
    val name = "defaultQueue"
    val jobName = s"SendEmailJob-$name"

    val configuration = Configuration(
      s"scheduling.$jobName.initialDelay"              -> "100 milliseconds",
      s"scheduling.$jobName.interval"                  -> "200 milliseconds",
      "scheduling.clean-suppression-list.initialDelay" -> "1 second",
      "scheduling.clean-suppression-list.interval"     -> "10 seconds",
      "suppression-list-delete-domain-list"            -> Seq()
    )

    val mockService = mock[Outbox]
    val lifecycle = mock[ApplicationLifecycle]
    val failedAfter: Long = 5L

    val (probeSubscriber, probeSink) = TestSink.probe[Unit].preMaterialize()

    val scheduledJob =
      SendEmailJob(
        jobName,
        mockService,
        configuration,
        lifecycle,
        sink = probeSink
      )
  }
}

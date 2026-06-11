/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.test.Helpers.stubControllerComponents
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.connectors.EventEmitter
import uk.gov.hmrc.email.repositories.EmailEventsRepository
import uk.gov.hmrc.http.client.HttpClientV2

import scala.concurrent.ExecutionContext

class EventEmitterJobSpec extends SpecBase with ScalaFutures {

  val testKit = ActorTestKit()
  implicit val system: ActorSystem = testKit.system.classicSystem
  implicit val ec: ExecutionContext = system.dispatcher
  implicit lazy val materializer: Materializer = Materializer(system)

  private val mockHttpClient = mock[HttpClientV2]
  private val mockEmailEventsRepo = mock[EmailEventsRepository]
  private val mockConfiguration = mock[Configuration]
  private val lifecycle = mock[ApplicationLifecycle]

  "EventEmitterJob" should {

    val config = EventEmitterConfig("", "")
    "have a name that includes sender domain name" in {
      val (probeSubscriber, probeSink) = TestSink.probe[Unit].preMaterialize()

      EventEmitterJob(
        "hmrc",
        mockHttpClient,
        config,
        mockEmailEventsRepo,
        mockConfiguration,
        lifecycle,
        probeSink
      ).name mustBe "hmrc-event-emitter"
    }

    "have a config key that does not include sender domain name" in {
      val (probeSubscriber, probeSink) = TestSink.probe[Unit].preMaterialize()

      EventEmitterJob(
        "hmrc",
        mockHttpClient,
        config,
        mockEmailEventsRepo,
        mockConfiguration,
        lifecycle,
        probeSink
      ).configKey mustBe "event-emitter"
    }
  }

  val httpClient = mock[HttpClientV2]
  val eventEmitterConfig = new EventEmitterConfig("", "")
  val emailEventsRepository = mock[EmailEventsRepository]
  val controllerComponents = stubControllerComponents()

  val eventEmitter = new EventEmitter(httpClient, eventEmitterConfig, emailEventsRepository)

  "EventEmitter" should {
    "not replace an obsolete event URL when using localhost" in {
      val localhostUrl = "http://localhost:8080/path"
      val result = eventEmitter.replace(localhostUrl)
      result mustBe localhostUrl
    }

  }

}

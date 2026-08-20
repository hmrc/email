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

import com.github.tomakehurst.wiremock.client.WireMock.*
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{ Seconds, Span }
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import test.TestConfig
import uk.gov.hmrc.email.model.Tag
import uk.gov.hmrc.email.repositories.{ EventHubItem, EventHubRepository }
import uk.gov.hmrc.mongo.workitem.ProcessingStatus
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{ Failed, PermanentlyFailed, Succeeded }
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

object WireMockSupport {
  // We have to make the wireMockPort constant per-JVM instead of constant
  // per-WireMockSupport-instance because config values containing it are
  // cached in the GGConfig object
  lazy val wireMockPort = 23232
}

class EventHubStreamISpec
    extends PlaySpec with WireMockTestSupport with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach
    with IntegrationPatience {

  object SpecConfig {
    val config: Map[String, Any] = Map(
      "microservice.services.event-hub.host"     -> "localhost",
      "microservice.services.event-hub.port"     -> WireMockTestSupport.wireMockPort,
      "streams.event-hub.event-polling-interval" -> "400.millis",
      "streams.event-hub.max-retries"            -> "3",
      "streams.event-hub.elements-per"           -> "100.millis",
      "streams.event-hub.elements"               -> "6",
      "event-hub.failedBefore"                   -> "500.millis",
      "event-hub.failedBefore"                   -> "500.millis",
      "event-hub.enabled"                        -> true
    )
  }

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++ SpecConfig.config ++ TestConfig.services ++ TestConfig.isImiConnector

  protected lazy val repo = app.injector.instanceOf[EventHubRepository]

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val detectedTimeStamp = "2021-08-27T10:53:07.778Z"
  val detectedDateTime = Instant.parse(detectedTimeStamp)
  val eventId = UUID.randomUUID()
  val event =
    EventHubItem(
      "event_id",
      eventId,
      s"test1@gmail.com",
      detectedDateTime,
      "failed",
      "some reason",
      Map("enrolment" -> "some enrolment", "templateId" -> "template-id"),
      Some(500),
      "message_id",
      Some(Tag("template-id"))
    )

  "when there are event hub items in the event-hub collection" should {
    "eventually they will be sent to the event-hub service and marked as 'succeeded' upon a successful processing and transfer" in {
      wireMockServer.stubFor(
        post(urlEqualTo("/publish/email"))
          .withHeader("Content-Type", equalTo("application/json"))
          .withRequestBody(matchingJsonPath("$.[?(@.subject == 'email')]"))
          .withRequestBody(matchingJsonPath("$.[?(@.groupId == 'message_id')]"))
          .withRequestBody(matchingJsonPath("$.[?(@.event.id == 'event_id')]"))
          .withRequestBody(matchingJsonPath("$.[?(@.event.detected == '" + detectedTimeStamp + "')]"))
          .withRequestBody(matchingJsonPath("$.[?(@.event.event == 'failed')]"))
          .withRequestBody(matchingJsonPath("$.[?(@.event.reason == 'some reason')]"))
          .withRequestBody(
            matchingJsonPath("$.[?(@.event.tags == {'enrolment':'some enrolment', 'templateId' : 'template-id'})]")
          )
          .withRequestBody(matchingJsonPath("$.[?(@.event.code == 500)]"))
          .willReturn(aResponse().withStatus(Status.CREATED))
      )

      repo.pushEventHubItem(event).futureValue
      eventually(timeout = Timeout(Span(5, Seconds))) {
        repo.collection.countDocuments().toFuture().futureValue mustBe 1
      }

      eventually(timeout = Timeout(Span(2, Seconds))) {
        repo.collection.countDocuments().toFuture().futureValue mustBe 1
        repo.count(Succeeded).futureValue mustBe 1
        verify(1, postRequestedFor(urlMatching("/publish/email")))
      }
    }

    "eventually they will be sent to the event-hub service and marked as 'failed' upon a failure then " +
      "as permanently failed after a 'max retries' treshold has been reached" in {

        stubFor(
          post(urlEqualTo("/publish/email"))
            .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR))
        )

        eventually(timeout = Timeout(Span(5, Seconds))) {
          repo.pushEventHubItem(event).futureValue
          repo.collection.countDocuments().toFuture().futureValue mustBe 1
        }

        eventually(timeout = Timeout(Span(2, Seconds))) {
          repo.collection.countDocuments().toFuture().futureValue mustBe 1
          repo.count(Failed).futureValue mustBe 1
          verify(3, postRequestedFor(urlMatching("/publish/email")))
        }

        eventually(timeout = Timeout(Span(3, Seconds))) {
          repo.collection.countDocuments().toFuture().futureValue mustBe 1
          repo.count(PermanentlyFailed).futureValue mustBe 1
          verify(4, postRequestedFor(urlMatching("/publish/email")))
        }
      }
  }

  override def beforeAll(): Unit =
    super.beforeAll()

  override def afterAll(): Unit =
    super.afterAll()

  override def beforeEach(): Unit = {
    super.beforeEach()
    repo.collection.deleteMany(Filters.empty()).toFuture().futureValue
    repo.ensureIndexes().futureValue
    repo.collection.countDocuments().toFuture().futureValue mustBe 0
    ()
  }
}

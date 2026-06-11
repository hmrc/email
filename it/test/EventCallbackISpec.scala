/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import org.scalatest.{ BeforeAndAfterEach, Ignore }
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.libs.ws.writeableOf_JsValue
import play.api.test.Helpers.{ await, * }
import test.TestConfig
import uk.gov.hmrc.email.utils.DateTimeUtils
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.client.HttpClientV2
import java.net.URL
import java.time.temporal.ChronoUnit
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

@Ignore
class EventCallbackISpec extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach {

  override lazy val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  val hmrcMailgunDomain = "exampleDomain"
  val voaMailgunDomain = "test"

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      TestConfig.stopSchedulers ++
      TestConfig.includeAdminApi ++
      TestConfig.voa ++
      TestConfig.hmrc ++
      TestConfig.services

  def mailgunSentEmailsWithMailgunIds(domain: String): Seq[JsValue] =
    httpClient
      .get(s"${host("mailgun")}/v3/$domain/email-with-mailgun-id")
      .execute[HttpResponse]
      .futureValue
      .json
      .as[Seq[JsValue]]

  def mailgunIdOfMostRecentSentEmail(domain: String): String =
    mailgunSentEmailsWithMailgunIds(domain).map(e => (e \ "id").as[String]).head

  def mailgunLoad(domain: String, events: TestEvents): Future[HttpResponse] =
    httpClient.post(s"${host("mailgun")}/v3/$domain/events").withBody(Json.toJson(events)).execute[HttpResponse]

  def loadIntoMailgunStub(domain: String, testEvents: TestEvents): Unit = {
    mailgunLoad(domain, testEvents).futureValue.status mustBe Status.CREATED
    ()
  }

  def mailgunReset(): Future[HttpResponse] =
    httpClient.get(mailgunEventsUri("reset")).execute[HttpResponse]

  def `/test-only/:domain/email-admin/coordinate-bounce-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/coordinate-bounce-queue")

  def `/:domain/email`(domain: String): URL =
    resource(s"/$domain/email")

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  def `/:domain/bounces`(domain: String): URL =
    resource(s"/$domain/bounces")

  "Event URL" should {
    "be called exactly once when the message is passed on to mailgun" in new TestCaseWithSentEmail {

      val response = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue

      response.status mustBe Status.OK
      (response.json \ "emitted").as[Int] mustBe 1
      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(sentJson))
      )
    }

    "be called exactly once when the message permanently bounces" in new TestCaseWithSentEmail {
      private val testEvent =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEvent)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      val result = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue

      result.status mustBe Status.OK
      (result.json \ "emitted").as[Int] mustBe 1

      val response = httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      response.status mustBe 200

      val response2 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      response2.status mustBe 200
      (response.json \ "emitted").as[Int] mustBe 1

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(permanentBounceJson(testEvent), sentJson))
      )
    }

    "not emit an event for a temporary bounce, even in subsequent events" in new TestCaseWithSentEmail {

      val result = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result.status mustBe 200
      (result.json \ "emitted").as[Int] mustBe 1

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))

      private val temporaryEventHubItem =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "temporary")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(temporaryEventHubItem)))

      val result2 = httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result2.status mustBe 200

      val result3 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result3.status mustBe 200
      (result3.json \ "emitted").as[Int] mustBe 0
      (result3.json \ "failed").as[Int] mustBe 0

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(sentJson))
      )

      private val permanentEventHubItem =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(permanentEventHubItem)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      verify(exactlyTwice, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(permanentBounceJson(permanentEventHubItem), sentJson))
      )
    }

    "not emit an events for duplicates from mailgun" in new TestCaseWithSentEmail {
      private val testEvent1 =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEvent1)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(permanentBounceJson(testEvent1), sentJson))
      )

      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEvent1)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      val result = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue

      result.status mustBe 200
      (result.json \ "emitted").as[Int] mustBe 0
      (result.json \ "failed").as[Int] mustBe 0

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
    }

    "emit events for different domains in mailgun" in new TestCaseWithSentEmail {
      private val hmrcDomain = "hmrc"
      private val voaDomain = "voa"

      private val testEventHmrc1 =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      private val testEventVoa1 =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(voaMailgunDomain), severity = "permanent")

      httpClient
        .post(`/:domain/email`(hmrcDomain))
        .withBody(Json.parse(request("hmrc")))
        .execute[HttpResponse]
        .futureValue
        .status mustBe 202

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      private val testEventHmrc2 =
        bounce(
          mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain),
          severity = "permanent",
          secondsFromNow = 2
        )

      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEventHmrc1)))
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEventHmrc2)))
      loadIntoMailgunStub(voaMailgunDomain, TestEvents(Seq(testEventVoa1)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`(hmrcDomain))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200
      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`(voaDomain))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      val result = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`(hmrcDomain))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result.status mustBe 200
      (result.json \ "emitted").as[Int] mustBe 2

      val result2 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`(voaDomain))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result2.status mustBe 200
      (result2.json \ "emitted").as[Int] mustBe 1

      verify(exactlyTwice, postRequestedFor(urlMatching(callbackPath(hmrcDomain))))
      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath(voaDomain))))
    }

    "not emit an event more than once" in new TestCaseWithSentEmail {

      val result1 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result1.status mustBe 200
      (result1.json \ "emitted").as[Int] mustBe 1

      val result2 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result2.status mustBe 200
      (result2.json \ "emitted").as[Int] mustBe 0
      (result2.json \ "failed").as[Int] mustBe 0
      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
    }

    "merge calls together if an event is received before other ones have been processed" in new TestCaseWithSentEmail {
      private val bounceEvent =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      private val openEvent =
        opened(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), secondsFromNow = 2)

      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(bounceEvent, openEvent)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      val result1 = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue

      result1.status mustBe 200
      (result1.json \ "emitted").as[Int] mustBe 2

      verify(exactlyOnce, postRequestedFor(urlMatching(callbackPath("hmrc"))))
      verify(
        postRequestedFor(urlMatching(callbackPath("hmrc")))
          .withRequestBody(eventsJson(openedJson(openEvent), permanentBounceJson(bounceEvent), sentJson))
      )
    }

    "fail events when non-200 status codes are recevied" in new TestCaseWithSentEmail {
      stubFor(
        post(urlEqualTo(callbackPath("hmrc")))
          .willReturn(aResponse().withStatus(Status.INTERNAL_SERVER_ERROR))
      )
      val result = httpClient
        .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
      result.status mustBe 200
      (result.json \ "emitted").as[Int] mustBe 0
      (result.json \ "failed").as[Int] mustBe 1
    }

  }

  "Including emailSource in send email request" should {

    "create a bounce with an emailSource" in new TestCaseWithSentEmail(Some("preferences")) {

      private val testEvent =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEvent)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      httpClient
        .get(`/:domain/bounces`("hmrc"))
        .execute[HttpResponse]
        .futureValue
        .json
        .as[Seq[TestBounce]]
        .headOption
        .flatMap(_.emailSource) mustBe Some("preferences")
    }

    "do not include emailSource when not provided" in new TestCaseWithSentEmail {

      private val testEvent =
        bounce(mailgunId = mailgunIdOfMostRecentSentEmail(hmrcMailgunDomain), severity = "permanent")
      loadIntoMailgunStub(hmrcMailgunDomain, TestEvents(Seq(testEvent)))

      httpClient
        .post(`/test-only/:domain/email-admin/coordinate-bounce-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe 200

      httpClient
        .get(`/:domain/bounces`("hmrc"))
        .execute[HttpResponse]
        .futureValue
        .json
        .as[Seq[TestBounce]]
        .headOption
        .flatMap(_.emailSource) mustBe None
    }

  }

  abstract class TestCaseWithSentEmail(emailSource: Option[String] = None) {
    val someTemplateId = "newMessageAlert"
    val exactlyOnce = 1
    val exactlyTwice = 2

    def permanentBounceJson(event: TestEvent): String =
      s"""{ "event": "PermanentBounce", "detected": "${event.time}" }"""

    def openedJson(event: TestEvent): String =
      s"""{ "event": "Opened", "detected": "${event.time}" }"""

    val sentJson =
      """{ "event": "Sent" }""" // we don't try to match the detected time as we can't know exactly what it is

    def eventsJson(events: String*): StringValuePattern =
      equalToJson(
        s"""{ "events": [ ${events.mkString(",")} ] }""".stripMargin,
        false,
        false
      )

    def bounce(mailgunId: String, severity: String, secondsFromNow: Int = 1): TestEvent =
      TestEvent(
        time = DateTimeUtils.now.plusSeconds(secondsFromNow).truncatedTo(ChronoUnit.MILLIS),
        address = "a@b.com",
        messageId = mailgunId,
        code = None,
        severity = Some(severity)
      )

    def opened(mailgunId: String, secondsFromNow: Int = 1): TestEvent =
      TestEvent(
        time = DateTimeUtils.now.plusSeconds(secondsFromNow).truncatedTo(ChronoUnit.MILLIS),
        address = "a@b.com",
        messageId = mailgunId,
        code = None,
        severity = None,
        event = "opened"
      )

    def request(domain: String): String =
      emailSource
        .map { source =>
          s"""{
             |"to":["a@b.com"],
             |"templateId":"$someTemplateId",
             |"eventUrl": "http://localhost:${wireMockServer.port}${callbackPath(domain)}",
             |"emailSource": "$source"
             |}
        """.stripMargin
        }
        .getOrElse(
          s"""{
             |"to":["a@b.com"],
             |"templateId":"$someTemplateId",
             |"eventUrl": "http://localhost:${wireMockServer.port}${callbackPath(domain)}"
             |}
        """.stripMargin
        )

    httpClient
      .post(`/:domain/email`("hmrc"))
      .withBody(Json.parse(request("hmrc")))
      .execute[HttpResponse]
      .futureValue
      .status mustBe 202

    httpClient
      .post(`/:domain/email`("voa"))
      .withBody(Json.parse(request("voa")))
      .execute[HttpResponse]
      .futureValue
      .status mustBe 202

    httpClient
      .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
      .withBody(JsNull)
      .execute[HttpResponse]
      .futureValue
      .status mustBe 200
    httpClient
      .post(`/test-only/:domain/email-admin/process-email-queue`("voa"))
      .withBody(JsNull)
      .execute[HttpResponse]
      .futureValue
      .status mustBe 200
  }

  lazy val wireMockServer = new WireMockServer()

  def callbackPath(domain: String): String = s"/event/bananas/$domain"

  def `/test-only/:domain/email-admin/emit-events`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/emit-events")

  override def afterAll(): Unit = {
    super.afterAll()
    wireMockServer.stop()
  }

  override def beforeEach(): Unit = {
    wireMockServer.resetMappings()
    wireMockServer.resetRequests()
    wireMockServer.resetScenarios()

    await(mailgunReset())

    stubFor(
      post(urlEqualTo(callbackPath("hmrc")))
        .willReturn(aResponse().withStatus(Status.NO_CONTENT))
    )
    stubFor(
      post(urlEqualTo(callbackPath("voa")))
        .willReturn(aResponse().withStatus(Status.NO_CONTENT))
    )

    httpClient
      .post(`/test-only/:domain/email-admin/emit-events`("hmrc"))
      .withBody(JsNull)
      .execute[HttpResponse]
      .futureValue
      .status mustBe 200

    val result = httpClient
      .post(`/test-only/:domain/email-admin/emit-events`("voa"))
      .withBody(JsNull)
      .execute[HttpResponse]
      .futureValue

    result.status mustBe 200
    (result.json \ "failed").as[Int] mustBe 0
    ()
  }

}

/*
 * Copyright 2024 HM Revenue & Customs
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

import com.typesafe.config.Config
import org.bson.types.ObjectId
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.concurrent.{ Eventually, IntegrationPatience }
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.{ BeforeAndAfterEach, LoneElement }
import org.scalatestplus.mockito.MockitoSugar.mock
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsArray, JsNull, JsValue, Json }
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.libs.ws.{ EmptyBody, writeableOf_WsBody }
import play.api.test.Helpers.{ ACCEPTED, await, * }
import test.{ TestConfig, TimedUnit }
import uk.gov.hmrc.email.model.EmailQueueProcessingResults
import uk.gov.hmrc.email.repositories.{ EmailQueueRepository, EventAccess, EventsAccessRepository }
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import util.AuthHelper

import java.net.URL
import java.time.{ Duration, Instant }
import java.util.Base64
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

class SendEmailISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with TimedUnit with LoneElement
    with MongoSupport with Eventually with IntegrationPatience {

  val config = mock[Config]

  override def additionalConfig: Map[String, ?] =
    Map("crypto.key" -> "WrongKeyReplaceThiskey==") ++
      Map("metrics.jvm" -> false) ++
      TestConfig.isImiConnector ++
      TestConfig.stopSchedulers ++
      TestConfig.holdlistDomains ++
      TestConfig.hmrc ++ TestConfig.voa ++ TestConfig.replyToTemplates ++ TestConfig.includeAdminApi ++ TestConfig.services

  override def databaseName: String = testId.toString
  def decodeString(s: String): Map[String, String] = {
    val decodedBytes = Base64.getDecoder.decode(s)
    val decodedString = new String(decodedBytes)
    Json.parse(decodedString).as[Map[String, String]]
  }
  val defaultPatienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(60, Seconds)),
      interval = scaled(Span(150, Millis))
    )

  private val eventAccessTime = Instant.now()

  implicit override val patienceConfig: PatienceConfig = defaultPatienceConfig

  val hmrcMailgunDomain = "exampleDomain"
  val voaMailgunDomain = "test"

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  private def emailRepo(collectionName: String) =
    new EmailQueueRepository(
      collectionName,
      app.configuration,
      mongoComponent
    ) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  protected lazy val hmrcDefaultQueueRepo = emailRepo("hmrc_defaultQueue")
  protected lazy val hmrcBackgroundQueueRepo = emailRepo("hmrc_backgroundQueue")

  protected lazy val hmrcPriorityQueueRepo = emailRepo("hmrc_urgentQueue")

  protected lazy val voa_defaultQueue = emailRepo("voa_defaultQueue")
  protected lazy val voa_backgroundQueue = emailRepo("voa_backgroundQueue")
  protected lazy val voa_urgentQueue = emailRepo("voa_urgentQueue")

  protected lazy val eventAccessRespository = new EventsAccessRepository(mongoComponent)

  // messages service in Service Manager
  private lazy val messageUrl = "http://localhost:8910"

  def `/message/system/:id/send-alert`(id: String): URL =
    s"$messageUrl/message/system/$id/send-alert"

  def `/:domain/email`(domain: String): URL =
    resource(s"/$domain/email")

  lazy val authHelper: AuthHelper = app.injector.instanceOf[AuthHelper]

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  def mailgunSentEmails(domain: String): Seq[JsValue] =
    httpClient.get(mailgunEventsUri(domain)).execute[HttpResponse].futureValue.json.as[Seq[JsValue]]

  def mailgunLoad(domain: String, events: TestEvents): Future[HttpResponse] =
    httpClient.post(mailgunEventsUri(domain)).withBody(Json.toJson(events)).execute[HttpResponse]

  def loadIntoMailgunStub(domain: String, testEvents: TestEvents): Unit = {
    mailgunLoad(domain, testEvents) // must have have(status(Status.CREATED))
    ()
  }

  def loadIntoStub(email: String, consentStatus: Boolean) = {
    val payload = s"""{"channel":"email","address":"$email","consent":$consentStatus,"reason":"some reason4"}"""
    httpClient.post(loadConsentItemUri).withBody(Json.parse(payload)).execute[HttpResponse].futureValue
  }

  def getConsentItemFromStub(email: String) =
    httpClient.get(getConsentItemUri(email)).execute[HttpResponse].futureValue.json

  def sendEmails(domain: String, templateId: String, emailCount: Int, parameters: String = "{}") = {
    def request(to: String) =
      s"""{
         |"to":["$to"],
         |"templateId":"$templateId",
         |"parameters":$parameters
         |}
          """.stripMargin

    await(Future.sequence((1 to emailCount).map { to =>
      httpClient
        .post(`/:domain/email`(domain))
        .withBody(Json.parse(request(s"$to@test.com")))
        .execute[HttpResponse]
        .map(response => response.status mustBe 202)
    }))

  }

  def mailgunBounceDeletes(domain: String): Seq[JsValue] =
    httpClient
      .get(s"${host("mailgun")}/v3/$domain/bounce-deletes")
      .execute[HttpResponse]
      .futureValue
      .json
      .as[Seq[JsValue]]

  def `/test-only/:domain/email-admin/coordinate-bounce-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/coordinate-bounce-queue")

  def `/:domain/bounces`(domain: String): URL =
    resource(s"/$domain/bounces")

  def sortedBouncesByEvents(events: Seq[TestEvent]) =
    events.map(_.toBounce).reverse.toList

  def generateTestEvents(
    startingTestEvent: Int = 1,
    numberOfTestEvents: Int
  ): (TestEvents, List[TestBounce]) = {
    val endTestEvent = (startingTestEvent + numberOfTestEvents - 10) - 1

    val bouncedEvents =
      (startingTestEvent to endTestEvent).map(TestEvent.generateBounce)
    val openedEvents =
      (endTestEvent + 1 to endTestEvent + 10).map(TestEvent.generateOpened)

    val events = TestEvents(openedEvents ++ bouncedEvents)
    val orderedBounces = sortedBouncesByEvents(bouncedEvents)
    (events, orderedBounces)
  }

  def bouncedEmailAddress(index: Int) =
    testEventsAsOrderedBounces(index).emailAddress

  lazy val numberOfTestEvents = 200

  lazy val (testEvents, testEventsAsOrderedBounces) = generateTestEvents(
    numberOfTestEvents = numberOfTestEvents
  )

  "Verify an alert before sending" should {
    "fail the sending of the alert for a URL specifying an unknown message ID" in {
      val templateId = "newMessageAlert"
      val request = s"""{
                       |"to":["1@test.com"],
                       |"templateId":"$templateId",
                       |"onSendUrl": "${`/message/system/:id/send-alert`(new ObjectId().toString)}",
                       |"parameters":{}
                       |}
          """.stripMargin

      await(
        httpClient
          .post(`/:domain/email`("hmrc"))
          .withBody(Json.parse(request))
          .execute[HttpResponse]
          .map(response => response.status mustBe 202)
      )

      val result = await(
        httpClient
          .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
          .withBody(JsNull)
          .execute[HttpResponse]
          .map(_.json.as[EmailQueueProcessingResults].requeued mustBe 1)
      )
      sentEmails("1@test.com").length mustBe 0
    }
  }

  "Sending a templated email" should {

    "return BAD_REQUEST if address is invalid" in {
      val templateId = "verifyEmailAddress"
      val request: String =
        s"""{
           |"to":["a@b.com","invalid","c@d.com"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse: Future[HttpResponse] =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]
      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("email: not a valid email address")
      }

    }

    "return BAD_REQUEST if reply-to-address is invalid" in {
      val templateId = "reply-to-template"
      val request =
        s"""{
           |"to":["a@b.com","c@d.com"],
           |"replyToAddress":"invalid",
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse: Future[HttpResponse] =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]
      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("not a valid email address")
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails("a@b.com").length mustBe 0
      sentEmails("c@d.com").length mustBe 0
    }

    "return BAD_REQUEST if reply-to-address is valid but to address is non gov.uk" in {
      val templateId = "reply-to-template"
      val request =
        s"""{
           |"to":["a@b.com"],
           |"replyToAddress":"reply-to@b.com",
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]
      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("Cannot use Reply-To-Address when sending to a non gov.uk domain")
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      sentEmails("a@b.com").length mustBe 0
    }

    "return BAD_REQUEST if reply-to-address is used on a template not in the allowList" in {
      val templateId = "some-template"
      val request =
        s"""{
           |"to":["a@b.gov.uk"],
           |"replyToAddress":"reply-to@b.com",
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]

      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("Forbidden use of Reply-To-Address")
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      sentEmails("a@b.gov.uk").length mustBe 0
    }

    "return NOT_FOUND if to address is and empty list" in {
      val templateId = "verifyEmailAddress"
      val request =
        s"""{
           |"to":[],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]
      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("email: recipients list is empty")
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      sentEmails("").length mustBe 0
    }

    "return BAD_REQUEST if the template does not exist" in {
      val templateId = "a_template_name_that_doesn't_exist"
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]

      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include(s"Template $templateId does not exist")
      }

      val response = httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue

      response.status mustBe Status.OK
      sentEmails("a@b.com").length mustBe 0
      sentEmails("b@c.com").length mustBe 0
      sentEmails("c@d.com").length mustBe 0
      sentEmails("d@e.com").length mustBe 0
    }

    "return BAD_REQUEST if a domain configuration is missing" in {
      val templateId = "verifyEmailAddress"
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      val actualResponse =
        httpClient
          .post(`/:domain/email`("INVALID_DOMAIN"))
          .withBody(Json.parse(request))
          .execute[HttpResponse]

      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("Unknown domain: INVALID_DOMAIN")
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          JsNull
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails("a@b.com").length mustBe 0
      sentEmails("b@c.com").length mustBe 0
      sentEmails("c@d.com").length mustBe 0
      sentEmails("d@e.com").length mustBe 0
    }

    "send 24 emails throttled to about 12 per second (approx 24 x 84 milliseconds ~ 2 seconds) to the emailQueue for the hmrc domain" in {
      val minTolerance = 1000L
      val maxTolerance = 2000L
      val expectedApproxTime = 2000L

      sendEmails("hmrc", "newMessageAlert", 24)

      val executionTime: Long = timed {
        httpClient
          .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
          .withBody(
            EmptyBody
          )
          .execute[HttpResponse]
          .futureValue
          .status mustBe (Status.OK)
      }

      eventually(sentEmails must have(size(24)))
      executionTime must ((be >= expectedApproxTime - minTolerance) and (be <= expectedApproxTime + maxTolerance))
    }

    "send 4 emails throttled to about 1 per second to the emailQueue for the voa domain" in {
      val minTolerance = 1000L
      val maxTolerance = 2000L
      val expectedApproxTime = 4000L

      sendEmails("voa", "newMessageAlert", 4)

      val executionTime = timed {
        httpClient
          .post(`/test-only/:domain/email-admin/process-email-queue`("voa"))
          .withBody(
            JsNull
          )
          .execute[HttpResponse]
          .futureValue
          .status mustBe (Status.OK)
      }

      eventually(sentEmails must have(size(4)))
      executionTime must ((be >= expectedApproxTime - minTolerance) and (be <= expectedApproxTime + maxTolerance))
    }

    "send 6 emails throttled to about 250000 per day (approx 6 x 340 milliseconds ~ 2 seconds) to the" +
      " emailQueueBackground for the hmrc domain" in {
        val minTolerance = 1000L
        val maxTolerance = 2000L
        val expectedApproxTime = 2000L

        sendEmails("hmrc", "annual_tax_summaries_message_alert", 6)

        val executionTime = timed {
          httpClient
            .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
            .withBody(
              JsNull
            )
            .execute[HttpResponse]
            .futureValue
            .status mustBe (Status.OK)
        }
        eventually(sentEmails must have(size(6)))
        executionTime must ((be >= expectedApproxTime - minTolerance) and (be <= expectedApproxTime + maxTolerance))
      }

    "send 12 emails throttled to about 350000 per day (approx 12 at 4.05 per second = approx 3 seconds) to the" +
      " emailQueueBackground for the voa domain" in {
        val minTolerance = 1000L
        val maxTolerance = 2000L
        val expectedApproxTime = 3000L

        sendEmails("voa", "annual_tax_summaries_message_alert", 12)

        val executionTime = timed {
          httpClient
            .post(`/test-only/:domain/email-admin/process-email-queue`("voa"))
            .withBody(
              JsNull
            )
            .execute[HttpResponse]
            .futureValue
            .status mustBe (Status.OK)
        }
        eventually(sentEmails must have(size(12)))
        executionTime must ((be >= expectedApproxTime - minTolerance) and (be <= expectedApproxTime + maxTolerance))
      }

    "send a high-priority email" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"

      def request(templateId: String) =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{"verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      val templateId = "verifyEmailAddress"

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      await(hmrcPriorityQueueRepo.collection.countDocuments().toFuture()) must be(1)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails must have(size(1))
    }

    "send a background email" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"

      def request(templateId: String) =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{"verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      val templateId = "annual_tax_summaries_message_alert"
      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      await(hmrcBackgroundQueueRepo.collection.countDocuments().toFuture()) must be(1)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails must have(size(1))
    }

    "send a background email with valid reply-to-address" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"

      def request(templateId: String) =
        s"""{
           |"to":["a@b.gov.uk"],
           |"templateId":"$templateId",
           |"replyToAddress":"reply-to@b.com",
           |"parameters":{"verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      val templateId = "annual_tax_summaries_message_alert"
      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      await(hmrcBackgroundQueueRepo.collection.countDocuments().toFuture()) must be(1)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      val emails: Seq[JsValue] = sentEmails
      emails must have(size(1))

    }

    "send multiple send email requests, when email receives 1 request with multiple emails" in {
      val verificationLink = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"
      val staticUrlInConfig = "http://localhost:9032"
      val staticVersion = "2.230.0"
      val fromAddressInConfig = s"noreply@${TestConfig.hmrc("senderDomains.hmrc.name")}"
      val subjectInConfig =
        "HMRC electronic communications: verify your email address"
      val templateId = "verifyEmailAddress"
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com"],
           |"templateId":"$templateId",
           |"parameters":{"verificationLink":"$verificationLink"},
           |"auditData" :{"data1":"dataValue1"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      sentEmails.length mustBe 3

      val sentEmail1 = sentEmails("a@b.com").head
      (sentEmail1 \ "from").as[String] must be(fromAddressInConfig)
      ((sentEmail1 \ "to" \ "email").head.toString) must include("a@b.com")
      (sentEmail1 \ "content" \ "subject").as[String] must be(subjectInConfig)
      (sentEmail1 \ "content" \ "text").as[String] must (
        include regex s"$verificationLink" and
          startWith("Verify your email address")
      )

      val sentEmail2 = sentEmails("b@c.com").head
      (sentEmail2 \ "from").as[String] must be(fromAddressInConfig)
      ((sentEmail2 \ "to" \ "email").head.toString) must include("b@c.com")
      (sentEmail2 \ "content" \ "subject").as[String] must be(subjectInConfig)
      (sentEmail2 \ "content" \ "text").as[String] must (
        include regex s"$verificationLink" and
          startWith("Verify your email address")
      )

      val sentEmail3 = sentEmails("c@d.com").head
      (sentEmail3 \ "from").as[String] must be(fromAddressInConfig)
      ((sentEmail3 \ "to" \ "email").head.toString) must include("c@d.com")
      (sentEmail3 \ "content" \ "subject").as[String] must be(subjectInConfig)
      (sentEmail3 \ "content" \ "text").as[String] must (
        include regex s"$verificationLink" and
          startWith("Verify your email address")
      )

      (sentEmail1 \ "content" \ "html").as[String] must (
        include(s"""href="$verificationLink"""") and
          include regex "^\\s*<!DOCTYPE html>\\s*<html>" // and
      )
      (sentEmail1 \ "cc").asOpt[String] must be(None)
      (sentEmail1 \ "bcc").asOpt[String] must be(None)
      (sentEmail1 \ "callbackData").asOpt[String] must be(None)
    }

    "send cato email and return a 202" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"
      val templateId = "cato_access_invitation_template_id"

      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"$templateId",
           |"parameters":{"verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      val sentEmail = sentEmails.head

      val callbackData = (sentEmail \ "callbackData").as[String]

      decodeString(callbackData).keys must be(
        Set("regime", s"templateId", "platform", "ContactPolicyGroupId")
      )
    }

    "send agents allowListing email request and return a 202" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"
      val templateId = "agents_access_invitation_template_id"

      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"$templateId",
           |"parameters":{"name":"Geoff Fisher","verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe Status.ACCEPTED

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          JsNull
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      val sentEmail = sentEmails.head
      val callbackData = (sentEmail \ "callbackData").as[String]

      decodeString(callbackData).keys must be(
        Set("regime", s"templateId", "platform", "ContactPolicyGroupId")
      )
    }

    "send agents opt-in-exclude templated email and return a 202" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"
      val templateId = "agents_opt_in_exclude_template_id"

      val request =
        s"""{
           |"to":["a@b.com","b@c.com"],
           |"templateId":"$templateId",
           |"parameters":{"name":"Geoff Fisher","verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          JsNull
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      val sentEmail = sentEmails.head
      val callbackData = (sentEmail \ "callbackData").as[String]
      decodeString(callbackData).keys must be(
        Set("regime", s"templateId", "platform", "ContactPolicyGroupId")
      )
    }

    "send agents opt-in-rejoin email to Mailgun and return a 202" in {
      val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"
      val templateId = "agents_opt_in_rejoin_template_id"

      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"$templateId",
           |"parameters":{"name":"Geoff Fisher","verificationLink":"$exampleParameterValue"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)
      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          JsNull
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      val sentEmail = sentEmails.head
      val callbackData = (sentEmail \ "callbackData").as[String]
      decodeString(callbackData).keys must be(
        Set("regime", "templateId", "platform", "ContactPolicyGroupId")
      )
    }

    "send an email when consent is false initially, force flag true and deleteConsent should be called and email should de removed" in {
      val forceResendToEmailAddress = "test8@email.com"
      loadIntoStub(forceResendToEmailAddress, false)
      val templateId = "changeOfEmailAddress"
      val forceResendRequest =
        s"""{
           |"to":["$forceResendToEmailAddress"],
           |"templateId":"$templateId",
           |"parameters":{},
           |"force":true
           |}
            """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(forceResendRequest))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      getConsentItemFromStub("test8%40email.com").toString mustBe "[]"
    }

    "send an email when consent is already true force flag true and consent should be still true" in {
      val forceResendToEmailAddress = "test8@email.com"
      loadIntoStub(forceResendToEmailAddress, true)
      val templateId = "changeOfEmailAddress"
      val forceResendRequest =
        s"""{
           |"to":["$forceResendToEmailAddress"],
           |"templateId":"$templateId",
           |"parameters":{},
           |"force":true
           |}
            """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(forceResendRequest))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)
      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      (getConsentItemFromStub("test8@email.com").head \ "consent").as[Boolean] mustBe true
    }

    "send an email and not call contact policy endpoint if the force flag is set to false" in {
      resetContactPolicyStub()
      await(
        eventAccessRespository.collection
          .insertOne(EventAccess(eventAccessTime, s"hmrc_bouncesLastAccessed"))
          .toFuture()
      )
      val unbouncedEmailAddress = "test-unbounced@test.com"
      val templateId = "changeOfEmailAddress"
      val request =
        s"""{
           |"to":["$unbouncedEmailAddress", "${bouncedEmailAddress(0)}"],
           |"templateId":"$templateId",
           |"parameters":{},
           |"force": false
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails.map(j => j \\ "email").map(_.toList).size mustBe 2
      getConsentItemFromStub("unbouncedEmailAddress") mustBe JsArray.empty
    }

    "not send emails twice if the email queue is processed twice" in {
      val templateId = "changeOfEmailAddress"
      val request =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails must have(size(1))
    }

    "not send emails to an address that matches a configured domain" in {
      import test.TestConfig.*
      val templateId = "changeOfEmailAddress"
      val requestA: String =
        s"""{
           |"to":["anything@${`specific.denylistedb.co.uk`}"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin
      val requestB: String =
        s"""{
           |"to":["anything@${`denylisteda.com`}"],
           |"templateId":"$templateId",
           |"parameters":{}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(requestA))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(requestB))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(
          EmptyBody
        )
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)
      sentEmails must be(empty)
    }
  }

  "Alert Queue Validity checks" should {
    val templateId = "verifyEmailAddress"
    val exampleParameterValue = "http://www.hmrc.co.uk/verify/SOMEOBJECTID"

    "store SendEmailRequest to given alertQueue" in {
      val request: String =
        s"""{
           | "to":["a@b.com","c@d.com"],
           | "templateId":"$templateId",
           | "parameters":{"verificationLink":"$exampleParameterValue"},
           | "alertQueue":"PRIORITY"
           |}
          """.stripMargin

      val actualResponse: Future[HttpResponse] =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]

      actualResponse.futureValue.status mustBe ACCEPTED
    }

    "return BAD_REQUEST if alertQueue is empty" in {
      val request: String =
        s"""{
           | "to":["a@b.com","c@d.com"],
           | "templateId":"$templateId",
           | "parameters":{"verificationLink":"$exampleParameterValue"},
           | "alertQueue":""
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]

      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("alertQueue: invalid alert queue provided")
      }
    }

    "return BAD_REQUEST if invalid alertQueue provided" in {
      val request: String =
        s"""{
           | "to":["a@b.com","c@d.com"],
           | "templateId":"$templateId",
           | "parameters":{"verificationLink":"$exampleParameterValue"},
           | "alertQueue":"PRIRITY"
           |}
          """.stripMargin

      val actualResponse =
        httpClient.post(`/:domain/email`("hmrc")).withBody(Json.parse(request)).execute[HttpResponse]

      whenReady(actualResponse.failed) { exception =>
        val exceptionMessage = exception.getMessage
        exceptionMessage must include(Status.BAD_REQUEST.toString)
        exception.getMessage must include("alertQueue: invalid alert queue provided")
      }
    }
  }

  override def beforeEach(): Unit = {

    await(mailgunReset())
    eventually(resetEmailsSent)
    await(hmrcDefaultQueueRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(hmrcPriorityQueueRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(hmrcBackgroundQueueRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_defaultQueue.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_backgroundQueue.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_urgentQueue.collection.deleteMany(Filters.empty()).toFuture())
    await(eventAccessRespository.collection.deleteMany(Filters.empty()).toFuture())
    ()
  }

  def mailgunReset(): Future[HttpResponse] =
    httpClient.get(s"$mailgunResetUri").execute[HttpResponse]

}

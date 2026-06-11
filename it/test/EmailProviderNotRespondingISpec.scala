/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import org.mongodb.scala.{ ObservableFuture, SingleObservableFuture }
import org.mongodb.scala.model.Filters
import org.scalatest.matchers.Matcher
import org.scalatest.{ BeforeAndAfterEach, LoneElement }
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsNull, Json }
import play.api.libs.ws.{ WSResponse, writeableOf_JsValue }
import play.api.test.Helpers.{ await, * }
import test.TestConfig
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.Failed
import java.net.URL
import java.time.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

class EmailProviderNotRespondingISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with LoneElement
    with MongoSupport {

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  def `be valid`: Matcher[Future[WSResponse]] =
    have(status(Status.OK)) and have(jsonContent("""{ "valid": true }"""))

  val sampleValidEmail = "valid@mail.com"

  val sampleTextNotInEmailFormat = "abcde"

  val sampleValidEmailUsingForeignCharacters = "someone@电子邮件地址.com.cn"

  override def additionalConfig: Map[String, ?] =
    Map(
      "metrics.jvm"                        -> false,
      "microservice.services.imi.host"     -> "localhost",
      "microservice.services.imi.port"     -> 88086, // Deliberately wrong port to simulate service down
      "microservice.services.mailgun.host" -> "localhost",
      "microservice.services.mailgun.port" -> 8185
    ) ++
      TestConfig.isImiConnector ++
      TestConfig.stopSchedulers ++
      TestConfig.includeAdminApi ++
      TestConfig.voa ++
      TestConfig.hmrc

  override def databaseName: String = testId.toString

  private def emailRepo(collectionName: String) =
    new EmailQueueRepository(collectionName, app.configuration, mongoComponent) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  def `/:domain/email`(domain: String): URL =
    resource(s"/$domain/email")

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  protected lazy val emailQueueRepo = emailRepo("hmrc_defaultQueue")

  def `be invalid`: Matcher[Future[WSResponse]] =
    have(status(Status.OK)) and have(jsonContent("""{ "valid": false }"""))

  "When email provider is down, requests to send an email message" should {
    "be marked as failed when force is set to false" in {
      val request: String =
        s"""{
           |"to":["test@test.com"],
           |"templateId":"newMessageAlert",
           |"parameters":{},
           |"force":false
           |}
          """.stripMargin

      val sendEmailResponse =
        httpClient
          .post(`/:domain/email`("hmrc"))
          .withBody(Json.parse(request))
          .execute[HttpResponse]
          .futureValue
          .status must be(Status.ACCEPTED)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status must be(Status.OK)

      emailQueueRepo.collection.find().toFuture().futureValue.loneElement.status mustBe Failed
    }

    "be marked as failed when force is set to true" in {
      val request: String =
        s"""{
           |"to":["test@test.com"],
           |"templateId":"newMessageAlert",
           |"parameters":{},
           |"force":true
           |}
          """.stripMargin

      val result2 = httpClient
        .post(`/:domain/email`("hmrc"))
        .withBody(Json.parse(request))
        .execute[HttpResponse]
        .futureValue

      result2.status mustBe Status.ACCEPTED

      val result = httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue

      result.status mustBe Status.OK
      emailQueueRepo.collection.find().toFuture().futureValue.loneElement.status mustBe Failed
    }
  }

  "When Email provider is down, processing the email queue" should {
    "not dequeue any queued emails" in {
      // We don't have a way of seeing whether we tried to send something to email provider, so we have to look at the repo :-(
      val request: String =
        s"""{
           |"to":["test@test.com"],
           |"templateId":"newMessageAlert",
           |"parameters":{}
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
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe Status.OK

      emailQueueRepo.collection.find().toFuture().futureValue.loneElement.status mustBe Failed
    }
  }

  override def beforeEach(): Unit = {
    await(emailQueueRepo.collection.deleteMany(Filters.empty()).toFuture())
    ()
  }
}

/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import org.mongodb.scala.SingleObservableFuture
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.libs.ws.writeableOf_JsValue
import test.TestConfig
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import java.time.Duration
import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

class TransactionEngineEmailISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with MongoSupport
    with Eventually {

  override def databaseName: String = testId.toString

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      TestConfig.isImiConnector ++
      TestConfig.transactionengine ++
      TestConfig.stopSchedulers ++
      TestConfig.holdlistDomains ++ TestConfig.includeAdminApi ++ TestConfig.voa ++ TestConfig.hmrc ++ TestConfig.services

  def `/:domain/email`(domain: String) =
    resource(s"/$domain/email")

  protected lazy val transactionengine_urgentQueue = emailRepo("transactionengine_urgentQueue")

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

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String) =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  "Sending a templated email" should {
    "send an email" in {
      val templateId = "transactionEngineHMRCSASA100Success"

      def request(templateId: String): String =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{"receivedDate":"01/01/01", "identifier":"123", "subject":"subject", "templateId" : "templateId"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("transactionengine"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue
        .status must be(Status.ACCEPTED)

      transactionengine_urgentQueue.collection.countDocuments().toFuture().futureValue must be(1)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("transactionengine"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails must have(size(1))
    }
  }

  override def beforeEach(): Unit = {
    resetEmailsSent
    ()
  }
}

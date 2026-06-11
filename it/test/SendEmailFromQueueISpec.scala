/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.{ BeforeAndAfterEach, LoneElement }
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsNull, Json }
import play.api.libs.ws.writeableOf_JsValue
import play.api.test.Helpers.*
import test.{ TestConfig, TimedUnit }
import uk.gov.hmrc.email.model.EmailQueueProcessingResults
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.mongo.test.MongoSupport
import java.net.URL
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

class SendEmailFromQueueISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with TimedUnit with LoneElement
    with MongoSupport with Eventually {

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      TestConfig.stopSchedulers ++
      TestConfig.holdlistDomains ++
      Map("imi.threshold" -> 100) ++
      Map(
        "senderDomains.hmrc.name"                 -> "exampleDomain",
        "senderDomains.hmrc.collection.bounce"    -> "bounce",
        "senderDomains.hmrc.imiConnector"         -> true,
        "senderDomains.hmrc.renderer"             -> "hmrc-email-renderer",
        "senderDomains.hmrc.defaultQueue.rate"    -> "1030000/day",
        "senderDomains.hmrc.backgroundQueue.rate" -> "250000/day"
      ) ++ TestConfig.includeAdminApi ++ TestConfig.services
  override def databaseName: String = testId.toString

  val defaultPatienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(60, Seconds)),
      interval = scaled(Span(150, Millis))
    )
  implicit override val patienceConfig: PatienceConfig = defaultPatienceConfig

  "Send email" should {
    "be successful" in new SetUp {
      val templateId = "newMessageAlert"
      val request = s"""{
                       |"to":["test@test.com"],
                       |"templateId":"$templateId",
                       |"parameters":{}
                       |}
          """.stripMargin
      await(
        httpClient
          .post(`/:domain/email`("hmrc"))
          .withBody(Json.parse(request))
          .execute[HttpResponse]
          .map(response => response.status)
      ) mustBe 202

      val result = await(
        httpClient
          .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
          .withBody(JsNull)
          .execute[HttpResponse]
      ).json.as[EmailQueueProcessingResults]
      result.sent mustBe 1

      sentEmails("test@test.com").size mustBe 1
    }
  }

  class SetUp {

    lazy val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]

    def `/:domain/email`(domain: String): URL =
      resource(s"/$domain/email")

    def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
      resource(s"/test-only/$domain/email-admin/process-email-queue")
  }

  override def beforeEach(): Unit = {
    resetEmailsSent
    ()
  }
}

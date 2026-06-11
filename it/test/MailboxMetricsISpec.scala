/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import play.api.test.Helpers.{ await, * }
import test.TestConfig
import uk.gov.hmrc.email.metrics.MailboxMetrics
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import java.net.URL
import java.time.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

class MailboxMetricsISpec
    extends PlaySpec with EmailBaseISpec with ScalaFutures with ResponseMatchers with BeforeAndAfterEach
    with MongoSupport {

  var mailboxMetrics = app.injector.instanceOf[MailboxMetrics]

  private def emailRepo(collectionName: String) =
    new EmailQueueRepository(
      collectionName,
      app.configuration,
      mongoComponent
    ) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  lazy val hmrcQueueRepo = emailRepo("hmrc_defaultQueue")
  lazy val hmrcQueuePriorityRepo = emailRepo("hmrc_urgentQueue")
  lazy val hmrcQueueBackgroundRepo = emailRepo("hmrc_backgroundQueue")
  lazy val voa_defaultQueue = emailRepo("voa_defaultQueue")
  lazy val voa_backgroundQueue = emailRepo("voa_backgroundQueue")
  lazy val voa_urgentQueue = emailRepo("voa_urgentQueue")

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      TestConfig.stopSchedulers ++
      TestConfig.includeAdminApi ++
      TestConfig.voa ++
      TestConfig.hmrc ++
      TestConfig.services

  def `/:domain/email`(domain: String): URL =
    resource(s"/$domain/email")

  def sendEmails(domain: String, templateId: String, emailCount: Int, parameters: String = "{}") = {

    def request(to: String) =
      s"""{
         |"to":["$to"],
         |"templateId":"$templateId",
         |"parameters":$parameters
         |}
          """.stripMargin

    import scala.concurrent.ExecutionContext.Implicits.global

    await(Future.sequence((1 to emailCount).map { to =>
      httpClient
        .post(`/:domain/email`(domain))
        .withBody(Json.parse(request(s"$to@test.com")))
        .execute[HttpResponse]
        .map(response => response.status mustBe 202)
    }))

  }

  "Mailbox metric sources " should {

    "return templates for all queues but Urgent" in {
      sendEmails("hmrc", "annual_tax_summaries_message_alert", 10)
      sendEmails("hmrc", "newMessageAlert", 6)
      sendEmails(
        "hmrc",
        "verifyEmailAddress",
        4,
        """{ "verificationLink": "someLink"}"""
      )

      sendEmails("voa", "newMessageAlert_SA316", 4)
      sendEmails("voa", "newMessageAlert", 3)
      sendEmails(
        "voa",
        "verifyEmailAddress",
        5,
        """{ "verificationLink": "someLink"}"""
      )

      val allMetrics = (mailboxMetrics.countByStatus ++ mailboxMetrics.countByTemplate)
        .map(_.metrics.futureValue)
        .fold(Map.empty)(_ ++ _)

      allMetrics must contain(
        "exampleDomain.queues.hmrc_backgroundQueue.templates.annual_tax_summaries_message_alert" -> 10
      )
      allMetrics must contain(
        "exampleDomain.queues.hmrc_defaultQueue.templates.newMessageAlert" -> 6
      )
      allMetrics.keySet must not contain "exampleDomain.queues.hmrc_urgentQueue.templates.verifyEmailAddress"

      allMetrics must not contain (
        "test.queues.voa_backgroundQueue.templates.newMessageAlert_SA316" -> 4
      )
      allMetrics must contain(
        "test.queues.voa_defaultQueue.templates.newMessageAlert" -> 3
      )
      allMetrics.keySet must not contain "test.queues.voa_urgentQueue.templates.verifyEmailAddress"
    }
  }

  override def beforeEach(): Unit = {
    await(hmrcQueueRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(hmrcQueuePriorityRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(hmrcQueueBackgroundRepo.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_defaultQueue.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_backgroundQueue.collection.deleteMany(Filters.empty()).toFuture())
    await(voa_urgentQueue.collection.deleteMany(Filters.empty()).toFuture())
    ()
  }
}

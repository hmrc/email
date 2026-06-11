/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

import org.scalatest.concurrent.Eventually
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsNull, Json }
import play.api.libs.ws.writeableOf_JsValue
import play.api.test.Helpers.await
import test.{ TestConfig, TimedUnit }
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import scala.concurrent.duration.*
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.{ implicitConversions, postfixOps }

class ShutdownWithRunningJobsISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with TimedUnit with Eventually {

  override def additionalConfig: Map[String, ?] =
    TestConfig.services

  def `/:domain/email`(domain: String) =
    resource(s"/$domain/email")

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String) =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  "Shutdown" should {

    "be possible without waiting for all pending emails to be sent" in {
      val aMinute: Long = 60 * 1000L
      val emailCountThatShouldTakeOverAMinuteToSendAll = 100
      def request(to: String): String =
        s"""{
           |"to":["$to"],
           |"templateId":"newMessageAlert",
           |"parameters":{}
           |}
          """.stripMargin
      await(Future.sequence((1 to emailCountThatShouldTakeOverAMinuteToSendAll).map { to =>
        httpClient
          .post(`/:domain/email`("hmrc"))
          .withBody(Json.parse(request(s"$to@test.com")))
          .execute[HttpResponse]
          .map(response => response.status mustBe 202)
      }))(2 minutes)

      val executedIn: Long = timed {
        httpClient
          .post(`/test-only/:domain/email-admin/process-email-queue`("hmrc"))
          .withBody(JsNull)
          .execute[HttpResponse]
        eventually {
          sentEmails.size must be >= 1
        }
        app.stop()
        ()
      }
      executedIn must be < aMinute
    }
  }

}

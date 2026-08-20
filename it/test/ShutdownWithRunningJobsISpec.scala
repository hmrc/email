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

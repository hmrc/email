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

import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.libs.ws.{ EmptyBody, WSResponse }
import play.api.test.Helpers.{ await, * }
import test.TestConfig
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.mongo.test.MongoSupport
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.ObservableFuture
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.HttpReads.Implicits._
import scala.language.implicitConversions
import java.net.URL
import java.time.Duration
import scala.concurrent.{ ExecutionContext, Future }

class FsetEmailISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with MongoSupport {

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      TestConfig.stopSchedulers ++
      TestConfig.holdlistDomains ++
      TestConfig.includeAdminApi ++
      TestConfig.voa ++
      TestConfig.hmrc ++ TestConfig.services

  implicit val ec: ExecutionContext = app.injector.instanceOf[ExecutionContext]

  override def databaseName: String = testId.toString

  private def emailRepo(collectionName: String) =
    new EmailQueueRepository(collectionName, app.configuration, mongoComponent) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  protected lazy val fset_urgentQueue = emailRepo("fset_urgentQueue")

  def `/:domain/email`(domain: String): URL =
    resource(s"/$domain/email")

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  def mailgunSentEmails(domain: String): Seq[JsValue] =
    httpClient.get(mailgunEmailUri(domain)).execute[HttpResponse].futureValue.json.as[Seq[JsValue]]

  val fsetMailgunDomain = "exampleDomain"

  "Sending a templated email" should {

    "send an email" in {

      def request(templateId: String): String =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{"programme":"fasttrack", "name":"myname"}
           |}
          """.stripMargin

      val templateId = "csr_app_submit_confirmation"

      val response = httpClient
        .post(`/:domain/email`("fset"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue

      response.body mustBe ""
      response.status must be(Status.ACCEPTED)

      await(fset_urgentQueue.collection.countDocuments().toFuture()) must be(1)

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("fset"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status must be(Status.OK)
      mailgunSentEmails(fsetMailgunDomain).length mustBe 1
    }
  }

  override def beforeEach(): Unit = {
    await(mailgunReset())
    ()
  }

  def mailgunReset() =
    httpClient.get(mailgunResetUri).execute[HttpResponse]

}

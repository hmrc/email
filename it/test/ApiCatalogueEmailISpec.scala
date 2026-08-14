/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mongodb.scala.SingleObservableFuture
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatestplus.play.PlaySpec
import play.api.http.Status
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.test.Helpers.{ await, * }
import test.TestConfig
import uk.gov.hmrc.email.repositories.EmailQueueRepository
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.mongo.test.MongoSupport
import java.net.URL
import java.time.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

class ApiCatalogueEmailISpec
    extends PlaySpec with EmailBaseISpec with ResponseMatchers with BeforeAndAfterEach with Eventually
    with MongoSupport {

  override def databaseName: String = testId.toString

  override def additionalConfig: Map[String, ?] =
    Map("metrics.jvm" -> false) ++
      Map("imi.threshold" -> 100) ++
      TestConfig.stopSchedulers ++
      TestConfig.holdlistDomains ++
      TestConfig.includeAdminApi ++
      TestConfig.voa ++
      TestConfig.hmrc ++
      TestConfig.apicatalogue ++
      TestConfig.services

  def `/:domain/email`(domain: String): URL = resource(s"/$domain/email")

  protected lazy val apicatalogue_urgentQueue = emailRepo("apicatalogue_urgentQueue")

  private def emailRepo(collectionName: String) =
    new EmailQueueRepository(collectionName, app.configuration, mongoComponent) {
      override lazy val inProgressRetryAfter = Duration.ofHours(1)

      override lazy val retryInterval = Duration.ofMillis(10000).toMillis
    }

  def `/test-only/:domain/email-admin/process-email-queue`(domain: String): URL =
    resource(s"/test-only/$domain/email-admin/process-email-queue")

  "Sending a templated email" should {
    "send an email" in {

      val templateId = "transactionEngineHMRCSASA100Success"
      def request(templateId: String): String =
        s"""{
           |"to":["a@b.com"],
           |"templateId":"$templateId",
           |"parameters":{"receivedDate":"01/01/01", "identifier":"123", "subject":"subject"}
           |}
          """.stripMargin

      httpClient
        .post(`/:domain/email`("apicatalogue"))
        .withBody(Json.parse(request(templateId)))
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.ACCEPTED)

      eventually {
        await(apicatalogue_urgentQueue.collection.countDocuments().toFuture()) must be(1)
      }

      httpClient
        .post(`/test-only/:domain/email-admin/process-email-queue`("apicatalogue"))
        .withBody(JsNull)
        .execute[HttpResponse]
        .futureValue
        .status mustBe (Status.OK)

      sentEmails must have(size(1))
    }
  }
  override def beforeEach(): Unit = {
    eventually(resetEmailsSent)
    ()
  }

}

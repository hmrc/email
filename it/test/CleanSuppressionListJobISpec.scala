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

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{ JsValue, Json }
import play.api.libs.ws.writeableOf_JsValue
import test.TestConfig
import uk.gov.hmrc.email.model.ConsentItem
import uk.gov.hmrc.email.scheduled.CleanSuppressionListJob
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.http.client.HttpClientV2
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

class CleanSuppressionListJobISpec extends PlaySpec with EmailBaseISpec {
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

  "clean" must {
    "complete" in new TestCase {
      resetSuppressionList()
      val cleanSuppressionListJob = app.injector.instanceOf[CleanSuppressionListJob]
      cleanSuppressionListJob.stream.start()
      getSuppressionList mustBe 0
    }

  }

  class TestCase {
    lazy val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]
    def resetSuppressionList(): Unit =
      httpClient.delete("http://localhost:8185/v1/contactpolicy").execute[HttpResponse].map(_ => ()).futureValue
    def send(id: Int): Future[Int] = {
      val body: JsValue = Json.parse(s"""{
                                        |  "channel": "email",
                                        |  "consent": true,
                                        |  "address": "test$id@gmail.com",
                                        |  "reason": "auto"
                                        |}""".stripMargin)

      val result = httpClient
        .post("http://localhost:8185/v1/groups/somegroupid/members")
        .withBody(body)
        .execute[HttpResponse]
        .map(_.status)
      result
    }

    def getSuppressionList: Int =
      httpClient
        .get("http://localhost:8185/v1/groups/somegroupid/members?format=JSON&pageSize=1000")
        .execute[HttpResponse]
        .futureValue
        .json
        .as[List[ConsentItem]]
        .size

    val testSize = 5

    for (i <- 1 to testSize)
      send(i).futureValue

    val result = Future.traverse((1 to 5).toList)(send).futureValue
    result.size mustBe 5
  }
}

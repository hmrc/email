/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.email.repositories.model

import play.api.libs.json.{ JsResultException, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.TestData.{ TEST_EMAIL_STATS_COUNT, TEST_STATS_NAME, TEST_TIME_INSTANT }

class EmailStatsSpec extends SpecBase {
  import EmailStats.format

  "Json Reads" should {
    "read the json correctly" in new Setup {
      Json.parse(emailStatsJson).as[EmailStats] mustBe emailStats
    }

    "throw the exception for invalid json" in new Setup {
      intercept[JsResultException] {
        Json.parse(emailStatsInvalidJson).as[EmailStats]
      }
    }
  }

  "Json Writes" should {
    "write the object correctly" in new Setup {
      Json.toJson(emailStats) mustBe Json.parse(emailStatsJson)
    }
  }

  trait Setup {
    val emailStats: EmailStats =
      EmailStats(name = TEST_STATS_NAME, count = TEST_EMAIL_STATS_COUNT, createdAt = TEST_TIME_INSTANT)

    val emailStatsJson: String =
      """{
        |"name":"test_name",
        |"count":10,
        |"createdAt":{"$date":{"$numberLong":"65478234"}}
        |}""".stripMargin

    val emailStatsInvalidJson: String =
      """{
        |"count":10,
        |"createdAt":{"$date":{"$numberLong":"65478234"}}
        |}""".stripMargin
  }
}

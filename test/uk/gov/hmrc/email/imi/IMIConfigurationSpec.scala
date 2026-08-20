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

package uk.gov.hmrc.email.imi

import org.scalatestplus.play.PlaySpec
import scala.language.implicitConversions

class IMIConfigurationSpec extends PlaySpec {

  "IMIConfiguration" must {
    "have correct consentUrl" in {
      val imiConfiguration = new IMIConfiguration {
        val imiBaseUrl: String = "http://imiBaseUrl"
        val imiConsentBaseUrl: String = "http://imiConsentBaseUrl"
      }
      imiConfiguration.consentUrl("groupId") mustBe "http://imiConsentBaseUrl/v1/groups/groupId/members"
    }
  }
}

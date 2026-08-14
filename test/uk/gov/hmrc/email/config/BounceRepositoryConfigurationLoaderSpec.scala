/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.email.config

import com.typesafe.config.{ ConfigException, ConfigFactory }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import scala.concurrent.duration.{ DAYS, Duration }

class BounceRepositoryConfigurationLoaderSpec extends SpecBase {

  private val mockConfiguration = mock[Configuration]

  private val configurationLoader = new BounceRepositoryConfigurationLoader(mockConfiguration: Configuration)
  private val domainWithoutRenderer = s"""{}"""
  private val expiryDays = 28

  "DomainConfigurationLoader" should {

    "load the domain configuration from the senderDomains element" in {
      val domainConfiguration = loadConfigFromString(s"""
                                                        |
                                                        |  bounce.expiry = 28 days
                                                        |
                                                        |""".stripMargin)
      domainConfiguration mustBe BounceRepositoryConfiguration(Duration(expiryDays.toLong, DAYS))
    }

    "throw a ConfigException if bounce.expiry is undefined" in {
      intercept[ConfigException] {
        loadConfigFromString(domainWithoutRenderer)
      }
    }
  }

  private def loadConfigFromString(config: String) =
    configurationLoader.load(ConfigFactory.parseString(config))

}

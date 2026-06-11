/*
 * Copyright 2023 HM Revenue & Customs
 *
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

/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import com.typesafe.config.ConfigFactory
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.services.*

class SenderDomainDefaultConfigurationSpec extends SpecBase {

  private val mockConfiguration = mock[Configuration]
  private val configurationLoader = new SenderDomainDefaultsConfigurationLoader(mockConfiguration)

  "DomainConfigurationDefaultLoader" should {

    "load the domain configuration from the senderDomains default if no value" in {
      val domainConfiguration =
        loadConfigFromString(s"""
                                |doNotUseInProductionEmailDomainAllowList: [ digital.hmrc.gov.uk ]
                                |""".stripMargin)
      domainConfiguration mustBe SenderDomainDefaultsConfiguration(
        doNotUseInProductionEmailDomainAllowList = Some(List("digital.hmrc.gov.uk"))
      )
    }

    "return empty configuration from the senderDomains default is no value" in {
      val domainConfiguration = loadConfigFromString(s"""
                                                        |senderDomains {
                                                        |}""".stripMargin)
      domainConfiguration mustBe SenderDomainDefaultsConfiguration(doNotUseInProductionEmailDomainAllowList = None)
    }

    "return empty configuration from the senderDomains default if empty value" in {
      val domainConfiguration = loadConfigFromString(s"""
                                                        |doNotUseInProductionEmailDomainAllowList: [ ]
                                                        |""".stripMargin)
      domainConfiguration mustBe SenderDomainDefaultsConfiguration(doNotUseInProductionEmailDomainAllowList = None)
    }
  }

  private def loadConfigFromString(config: String) =
    configurationLoader.load(ConfigFactory.parseString(config))

}

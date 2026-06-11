/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import com.typesafe.config.{ ConfigException, ConfigFactory }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.clusterworkthrottling.Rate
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.services.*
import scala.concurrent.duration.FiniteDuration

class SenderDomainConfigurationSpec extends SpecBase {

  private val mockConfiguration = mock[Configuration]
  private val configurationLoader = new SenderDomainConfigurationLoader(mockConfiguration)
  private val domainWithoutRenderer =
    """senderDomains.domainWithoutRenderer.foo = "bar""""
  private val defaultRate = 125000L
  private val backgroundRate = 5000L

  "DomainConfigurationLoader" should {

    "load the domain configuration from the senderDomains element" in {
      val domainConfiguration: SenderDomainConfiguration =
        loadConfigFromString(s"""
                                |senderDomains {
                                |
                                |  validDomain {
                                |    name: tax.service.gov.uk.test
                                |    renderer: validDomain-email-renderer
                                |    imiConnector: true
                                |    mailgun: {
                                |      apiKey = exampleApiKey
                                |      publicApiKey = examplePublicApiKey
                                |    }
                                |    imi {
                                |      apiKey = exampleApiKey
                                |      groupId = groupId
                                |    }
                                |    defaultQueue: {
                                |      collection: validDomain_defaultQueue
                                |      rate = 125000/day
                                |    }
                                |    urgentQueue: {
                                |      collection: validDomain_urgentQueue
                                |    }
                                |    backgroundQueue: {
                                |      collection: validDomain_backgroundQueue
                                |      rate = 5000/day
                                |    }
                                |    holdList: [ btinternet.com|btinternet.co.uk|btopenworld.com, "virgin.*" ]
                                |
                                |    events.collection = "mailgunEvents"
                                |
                                |    bounces.collection = "bounce"
                                |  }
                                |}""".stripMargin)("validDomain")
      domainConfiguration mustBe SenderDomainConfiguration(
        name = "tax.service.gov.uk.test",
        renderer = "validDomain-email-renderer",
        imiConnector = true,
        mailgun = MailgunApiKeys("exampleApiKey", "examplePublicApiKey"),
        imi = ImiApiConfig("exampleApiKey", "groupId"),
        holdList = Some(List("btinternet.com|btinternet.co.uk|btopenworld.com", "virgin.*")),
        defaultQueue = DefaultQueueConfiguration(
          Some("validDomain_defaultQueue"),
          Some(Rate(defaultRate, FiniteDuration(1, "day")))
        ),
        urgentQueue = UrgentQueueConfiguration(Some("validDomain_urgentQueue")),
        backgroundQueue = BackgroundQueueConfiguration(
          Some("validDomain_backgroundQueue"),
          Some(Rate(backgroundRate, FiniteDuration(1, "day")))
        ),
        bounces = BouncesConfiguration(Some("bounce")),
        events = EventsConfiguration(Some("mailgunEvents"))
      )
    }

    "throw a ConfigException when the 'renderer' element is undefined" in {
      intercept[ConfigException] {
        loadConfigFromString(domainWithoutRenderer)
      }
    }

    "throw a ConfigException when the 'senderDomains' element is undefined" in new {
      intercept[ConfigException] {
        loadConfigFromString("")
      }
    }

  }

  private def loadConfigFromString(config: String) =

    val configuration = Configuration(ConfigFactory.parseString(config))
    configurationLoader.load(configuration)

}

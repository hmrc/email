/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.config

import play.api.Application
import uk.gov.hmrc.email.SpecBase

class SenderDomainConfigurationLoaderSpec extends SpecBase {

  "metricConfig" should {
    "return correct size of metric config list" in new Setup {
      senderDomainConfigurationLoader.metricConfig.size must be(63)
    }
  }

  trait Setup {
    val app: Application = applicationBuilder
      .configure(
        "microservice.metrics.enabled" -> false,
        "metrics.enabled"              -> false,
        "auditing.enabled"             -> false
      )
      .build()

    val senderDomainConfigurationLoader: SenderDomainConfigurationLoader =
      app.injector.instanceOf[SenderDomainConfigurationLoader]
  }
}

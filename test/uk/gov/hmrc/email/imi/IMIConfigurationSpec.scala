/*
 * Copyright 2023 HM Revenue & Customs
 *
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

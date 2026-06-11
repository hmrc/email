/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import play.api.libs.json.{ JsResultException, JsString }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.services.Priority.{ Priority, background, default, priority, standard, urgent }

class SenderDomainConfigurationSpec extends SpecBase {

  "Priority.isDefault" should {
    "return correct value" in {
      Priority.isDefault(Some(standard)) must be(true)
      Priority.isDefault(Some(default)) must be(false)
      Priority.isDefault(Some(priority)) must be(false)
      Priority.isDefault(Some(background)) must be(false)
      Priority.isDefault(Some(urgent)) must be(false)

      Priority.isDefault("default") must be(true)
      Priority.isDefault("priority") must be(false)
    }
  }

  "Priority.isPriority" should {
    "return correct value" in {
      Priority.isPriority(Some(urgent)) must be(true)
      Priority.isPriority(Some(standard)) must be(false)
      Priority.isPriority(Some(default)) must be(false)
      Priority.isPriority(Some(background)) must be(false)
      Priority.isPriority(Some(priority)) must be(false)

      Priority.isPriority("priority") must be(true)
      Priority.isPriority("default") must be(false)
    }
  }

  "Priority.isBackground" should {
    "return correct value" in {
      Priority.isBackground(Some(background)) must be(true)
      Priority.isBackground(Some(standard)) must be(false)
      Priority.isBackground(Some(default)) must be(false)
      Priority.isBackground(Some(priority)) must be(false)
      Priority.isBackground(Some(urgent)) must be(false)

      Priority.isBackground("background") must be(true)
      Priority.isBackground("priority") must be(false)
    }
  }

  "Priority.priorityReads" should {
    import Priority.priorityReads

    "read the json correctly" in {
      JsString("standard").as[Priority] mustBe standard
    }

    "throw the exception for the invalid json" in {
      intercept[JsResultException] {
        JsString("unknown").as[Priority]
      }
    }
  }
}

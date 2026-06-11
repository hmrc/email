/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.emailaddress

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{ JsError, JsString, JsSuccess, Json }

class PlayJsonFormatsSpec extends AnyWordSpec with Matchers {

  import PlayJsonFormats._

  "Reading an EmailAddress from JSON" should {

    "work for a valid email address" in {
      val result = JsString("a@b.com").validate[EmailAddress]
      result shouldBe a[JsSuccess[?]]
      result.get should be(EmailAddress("a@b.com"))
    }

    "fail for a invalid email address" in {
      val result = JsString("ab.com").validate[EmailAddress]
      result shouldBe a[JsError]
    }
  }

  "Writing an EmailAddress to JSON" should {

    "work!" in {
      Json.toJson(EmailAddress("a@b.com")) should be(JsString("a@b.com"))
    }
  }
}

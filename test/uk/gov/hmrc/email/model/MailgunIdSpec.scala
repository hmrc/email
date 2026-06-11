/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import play.api.libs.json.JsString
import uk.gov.hmrc.email.SpecBase

class MailgunIdSpec extends SpecBase {

  "MailgunId" should {
    "leave id as id" in {
      JsString("id").as[MailgunId] mustBe MailgunId("id")
    }

    "convert <id to id" in {
      JsString("<id").as[MailgunId] mustBe MailgunId("id")
    }

    "convert id> to id" in {
      JsString("id>").as[MailgunId] mustBe MailgunId("id")
    }

    "convert <id> to id" in {
      JsString("<id>").as[MailgunId] mustBe MailgunId("id")
    }

    "convert id> (stuff) to id" in {
      JsString("5526D432012B86BD@rgout02.bt.lon5.cpcloud.co.uk> (added by postmaster@btinternet.com)")
        .as[MailgunId] mustBe
        MailgunId("5526D432012B86BD@rgout02.bt.lon5.cpcloud.co.uk (added by postmaster@btinternet.com)")
    }

    "convert (stuff)  id> to id" in {
      JsString("(added by postmaster@btinternet.com) 5526D432012B86BD@rgout02.bt.lon5.cpcloud.co.uk>")
        .as[MailgunId] mustBe
        MailgunId("(added by postmaster@btinternet.com) 5526D432012B86BD@rgout02.bt.lon5.cpcloud.co.uk")
    }

    "not be able to start or end with chevrons" in {
      an[IllegalArgumentException] should be thrownBy MailgunId("<id>")
      an[IllegalArgumentException] should be thrownBy MailgunId("id>")
      an[IllegalArgumentException] should be thrownBy MailgunId("<id")
    }
  }
}

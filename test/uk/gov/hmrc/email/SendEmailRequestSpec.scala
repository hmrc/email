/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import play.api.libs.json.Json
import uk.gov.hmrc.email.controllers.model.SendEmailRequest
import uk.gov.hmrc.email.emailaddress.EmailAddress

class SendEmailRequestSpec extends SpecBase {
  "Deserialising the request" should {
    "work if all parameters are supplied" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"force":true,
           |"auditData":{"data1":"dataValue1", "data2":"dataValue2"},
           |"onSendUrl":"http://on/send/url",
           |"eventUrl":"http://my/test/url"
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] mustBe SendEmailRequest(
        to = List("a@b.com", "b@c.com", "c@d.com", "d@e.com").map(EmailAddress.apply),
        templateId = "templateIdValue",
        parameters = Map("param1" -> "value1", "param2" -> "value2"),
        force = true,
        eventUrl = Some("http://my/test/url"),
        onSendUrl = Some("http://on/send/url"),
        auditData = Map("data1" -> "dataValue1", "data2" -> "dataValue2")
      )
    }

    "work if all tags are supplied" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"tags": {"enrolment": "IR-NINO~NINO~AB123456C", "messageId": "123456", "source":"gmc"},
           |"force":true,
           |"auditData":{"data1":"dataValue1", "data2":"dataValue2"},
           |"onSendUrl":"http://on/send/url",
           |"eventUrl":"http://my/test/url"
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] mustBe SendEmailRequest(
        to = List("a@b.com", "b@c.com", "c@d.com", "d@e.com").map(EmailAddress.apply),
        templateId = "templateIdValue",
        parameters = Map("param1" -> "value1", "param2" -> "value2"),
        tags = Map("enrolment" -> "IR-NINO~NINO~AB123456C", "messageId" -> "123456", "source" -> "gmc"),
        force = true,
        eventUrl = Some("http://my/test/url"),
        onSendUrl = Some("http://on/send/url"),
        auditData = Map("data1" -> "dataValue1", "data2" -> "dataValue2")
      )
    }

    "work if all parameters are supplied with 'PRIORITY' AlertQueue" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"force":true,
           |"auditData":{"data1":"dataValue1", "data2":"dataValue2"},
           |"onSendUrl":"http://on/send/url",
           |"eventUrl":"http://my/test/url",
           |"alertQueue":"PRIORITY"
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] mustBe SendEmailRequest(
        to = List("a@b.com", "b@c.com", "c@d.com", "d@e.com").map(EmailAddress.apply),
        templateId = "templateIdValue",
        parameters = Map("param1" -> "value1", "param2" -> "value2"),
        force = true,
        eventUrl = Some("http://my/test/url"),
        onSendUrl = Some("http://on/send/url"),
        auditData = Map("data1" -> "dataValue1", "data2" -> "dataValue2"),
        alertQueue = Some("PRIORITY")
      )
    }

    "work if all parameters are supplied with 'BACKGROUND' AlertQueue" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"force":true,
           |"auditData":{"data1":"dataValue1", "data2":"dataValue2"},
           |"onSendUrl":"http://on/send/url",
           |"eventUrl":"http://my/test/url",
           |"alertQueue":"BACKGROUND"
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] mustBe SendEmailRequest(
        to = List("a@b.com", "b@c.com", "c@d.com", "d@e.com").map(EmailAddress.apply),
        templateId = "templateIdValue",
        parameters = Map("param1" -> "value1", "param2" -> "value2"),
        force = true,
        eventUrl = Some("http://my/test/url"),
        onSendUrl = Some("http://on/send/url"),
        auditData = Map("data1" -> "dataValue1", "data2" -> "dataValue2"),
        alertQueue = Some("BACKGROUND")
      )
    }

    "work if no auditData or callbackUrl or 'onSend' url or AlertQueue is supplied" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"force":true
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] mustBe SendEmailRequest(
        to = List("a@b.com", "b@c.com", "c@d.com", "d@e.com").map(EmailAddress.apply),
        templateId = "templateIdValue",
        parameters = Map("param1" -> "value1", "param2" -> "value2"),
        force = true,
        eventUrl = None,
        onSendUrl = None,
        auditData = Map.empty,
        alertQueue = None
      )
    }

    "default the force flag to false if not supplied" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"}
           |}
          """.stripMargin
      Json.parse(request).as[SendEmailRequest] must have(Symbol("force")(false))
    }

    "generate an exception if the force flag is not a valid value" in {
      val request =
        s"""{
           |"to":["a@b.com","b@c.com","c@d.com","d@e.com"],
           |"templateId":"templateIdValue",
           |"parameters":{"param1":"value1", "param2":"value2"},
           |"force":"some invalid value"
           |}
          """.stripMargin
      an[Exception] should be thrownBy Json.parse(request).as[SendEmailRequest]
    }
  }
}

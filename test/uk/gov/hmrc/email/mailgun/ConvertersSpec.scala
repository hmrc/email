/*
 * Copyright 2026 HM Revenue & Customs
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

package uk.gov.hmrc.email.mailgun

import org.scalatest.Inside
import play.api.libs.json._
import uk.gov.hmrc.email.model._
import uk.gov.hmrc.email.model.EventType._
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.emailaddress.EmailAddress
import java.time.Instant

class ConvertersSpec extends SpecBase with Inside {

  val mcf: Converters.type = Converters

  "MailgunEvent reads" must {
    "unmarshal all tags from json string" in {

      import uk.gov.hmrc.email.mailgun.Converters.readMailgunEvent

      val jsonWithExpectedTags = Json.parse("""
                                              |{
                                              |  "id" : "jxVuhYlhReaK3QsggHfFRA",
                                              |  "event" : "failed",
                                              |  "recipient": "test-200@test.com",
                                              |  "timestamp": 1396629292,
                                              |  "delivery-status": {
                                              |    "code": 700
                                              |  },
                                              |  "message": {
                                              |    "headers": {
                                              |      "message-id": "77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"
                                              |    }
                                              |  },
                                              |  "user-variables":{},
                                              |  "tags":["regime.sa", "template.SA_309"]
                                              |}
        """.stripMargin)

      val jsonWithAdditionalTags =
        Json.parse("""
                     |{
                     |  "id" : "jxVuhYlhReaK3QsggHfFRA",
                     |  "event" : "failed",
                     |  "recipient": "test-200@test.com",
                     |  "timestamp": 1396629292,
                     |  "delivery-status": {
                     |    "code": 700
                     |  },
                     |  "message": {
                     |    "headers": {
                     |      "message-id": "77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"
                     |    }
                     |  },
                     |  "user-variables":{},
                     |  "tags":["regime.sa", "template.SA_309", "anotherTag", "tagToBeIgnored"]
                     |}
        """.stripMargin)

      jsonWithExpectedTags
        .as[Option[MailgunEvent]] mustBe jsonWithAdditionalTags
        .as[Option[MailgunEvent]]
    }

    "handle event of type accepted" in {

      import uk.gov.hmrc.email.mailgun.Converters.readMailgunEvent

      def createEvent(eventType: String, severity: String = ""): JsValue =
        Json.parse(s"""
                      |{
                      |  "id" : "jxVuhYlhReaK3QsggHfFRA",
                      |  "event" : "$eventType",
                      |  "severity": "$severity",
                      |  "recipient": "test-200@test.com",
                      |  "timestamp": 1396629292,
                      |  "delivery-status": {
                      |    "code": 700
                      |  },
                      |  "message": {
                      |    "headers": {
                      |      "message-id": "77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"
                      |    }
                      |  },
                      |  "user-variables":{},
                      |  "tags":["regime.sa", "template.SA_309"]
                      |}
        """.stripMargin)

      createEvent("accepted")
        .as[Option[MailgunEvent]]
        .get
        .eventType mustBe Accepted
      createEvent("opened").as[Option[MailgunEvent]].get.eventType mustBe Opened
      createEvent("delivered")
        .as[Option[MailgunEvent]]
        .get
        .eventType mustBe Delivered
      createEvent("complained")
        .as[Option[MailgunEvent]]
        .get
        .eventType mustBe Complained
      createEvent("failed")
        .as[Option[MailgunEvent]]
        .get
        .eventType mustBe TemporaryBounce
      createEvent("failed", "permanent")
        .as[Option[MailgunEvent]]
        .get
        .eventType mustBe PermanentBounce
    }
  }

  "Mailgun event" must {
    import uk.gov.hmrc.email.mailgun.Converters.readMailgunEvent

    "parse from JSON correctly when message property is missing" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "user-variables":{},
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get must have(Symbol("mailgunId")(None))
    }
    "parse from JSON correctly when message/headers property is missing" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |
                 |  },
                 |  "user-variables":{},
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get must have(Symbol("mailgunId")(None))
    }
    "parse from JSON correctly when message/headers/message-id property is missing" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |    }
                 |  },
                 |  "user-variables":{},
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get must have(Symbol("mailgunId")(None))
    }
    "parse from JSON correctly when message/headers/message-id property is null" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{},
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get must have(Symbol("mailgunId")(None))
    }
    "parse from JSON correctly when user-variables is missing" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(None)
    }
    "parse from JSON correctly when user-variables exists but has no enrolment" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{},
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(None)
    }
    "parse from JSON correctly when user-variables has an enrolment" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{
                 |    "enrolment": "test-enrolment"
                 |  },
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(Some("test-enrolment"))
    }
    "parse from JSON correctly when user-variables has a variable that is not an enrolment" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{
                 |    "var1": "test-enrolment"
                 |  },
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(None)
    }
    "parse from JSON correctly when user-variables has a enrolment and other variables" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{
                 |    "var1": "dummy1",
                 |    "enrolment": "test-enrolment",
                 |    "var2": "dummy2"
                 |  },
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(Some("test-enrolment"))
    }
    "parse as None when user-variables has a non string value" in {
      Json
        .parse("""{
                 |  "severity": "permanent",
                 |  "timestamp": 1428998519.975162,
                 |  "delivery-status": {
                 |      "code": "5.1.1"
                 |  },
                 |  "log-level": "error",
                 |  "id": "OzfuBvicSJCijL6mBxgTwg",
                 |  "reason": "bounce",
                 |  "recipient": "fred.williams@fjwqs.co.uk",
                 |  "event": "failed",
                 |  "message": {
                 |    "headers": {
                 |      "message-id": null
                 |    }
                 |  },
                 |  "user-variables":{
                 |    "id": null,
                 |    "var2": "dummy2"
                 |  },
                 |  "tags":[]
                 |}
        """.stripMargin)
        .as[Option[MailgunEvent]]
        .get
        .enrolment must be(None)
    }
  }

  "Reading bounces from a mailgun event list" must {
    "work for a single item" in {
      val sampleJson = Json.parse("""
                                    |{
                                    |  "id" : "OzfuBvicSJCijL6mBxgTwg",
                                    |  "event" : "failed",
                                    |  "recipient": "test-200@test.com",
                                    |  "timestamp": 1396629292,
                                    |  "delivery-status": {
                                    |    "code": 700
                                    |  },
                                    |  "message": {
                                    |    "headers": {
                                    |      "message-id": "77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"
                                    |    }
                                    |  },
                                    |  "user-variables":{},
                                    |  "tags":["regime.sa", "template.SA_309", "unrecognisedTag"]
                                    |}
        """.stripMargin)
      val event =
        sampleJson.validate[Option[MailgunEvent]](mcf.readMailgunEvent)
      event.get.get must have(
        Symbol("eventType")(TemporaryBounce),
        Symbol("emailAddress")(EmailAddress("test-200@test.com")),
        Symbol("detected")(Instant.parse("2014-04-04T16:34:52.000Z")),
        Symbol("code")(Some(700)),
        Symbol("mailgunId")(Some(MailgunId("77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"))),
        Symbol("regime")(Some(RegimeTag("sa"))),
        Symbol("template")(Some(TemplateTag("SA_309")))
      )
    }

    "work for a sequence of items" in {
      val sampleJson = Json.parse(
        """
          |{
          |  "items": [
          |    {
          |      "id" : "jxVxhYlhReaK3QsggHfFRA",
          |      "event" : "failed",
          |      "recipient": "test-200@test.com",
          |      "timestamp": 1396629292,
          |      "delivery-status": {
          |        "code": 700
          |      },
          |     "message": {
          |       "headers": {
          |         "message-id": "1@example.com"
          |       }
          |     },
          |     "user-variables":{},
          |     "tags":["regime.sa", "template.SA_309"]
          |    },
          |    {
          |      "id" : "jxPuhYlhReaK3QsggHfFRA",
          |      "event" : "failed",
          |      "severity": "permanent",
          |      "recipient": "test-199@test.com",
          |      "timestamp": 1396629293,
          |      "delivery-status": {
          |        "code": 699
          |      },
          |      "message": {
          |         "headers": {
          |           "message-id": "2@example.com"
          |         }
          |       },
          |       "tags":["regime.sa", "template.SA_316"]
          |    },
          |    {
          |      "id" : "oxVuhYlhReaK3QsggHfFRB",
          |      "event" : "opened",
          |      "recipient": "test-198@test.com",
          |      "timestamp": 1396629294,
          |      "delivery-status": {
          |        "code": 698
          |      },
          |      "message": {
          |         "headers": {
          |           "message-id": "3@example.com"
          |         }
          |       },
          |       "user-variables":{},
          |       "tags":["regime.paye"]
          |    }
          |  ],
          |  "paging": {
          |    "next": "http:\/\/localhost:10029\/v3\/domain\/events\/YmVnaW4lM0RUdWUlMkMrMjUrTWFyKzIwMTQrMTclM0EzOCUzQTEzKyUyQjAwMDAmc2tpcCUzRDEwMA",
          |    "previous": "http:\/\/localhost:10029\/v3\/domain\/events\/previous"
          |  }
          |}
        """.stripMargin
      )
      val events = sampleJson.validate[EventsPage](mcf.readEventsAndNextPage)
      inside(events) { case JsSuccess(PageWithEvents(Seq(event1, event2, event3), next), _) =>
        event1 must have(
          Symbol("eventType")(TemporaryBounce),
          Symbol("emailAddress")(EmailAddress("test-200@test.com")),
          Symbol("detected")(Instant.parse("2014-04-04T16:34:52.000Z")),
          Symbol("code")(Some(700)),
          Symbol("mailgunId")(Some(MailgunId("1@example.com"))),
          Symbol("regime")(Some(RegimeTag("sa"))),
          Symbol("template")(Some(TemplateTag("SA_309")))
        )
        event2 must have(
          Symbol("eventType")(PermanentBounce),
          Symbol("emailAddress")(EmailAddress("test-199@test.com")),
          Symbol("detected")(Instant.parse("2014-04-04T16:34:53.000Z")),
          Symbol("code")(Some(699)),
          Symbol("mailgunId")(Some(MailgunId("2@example.com"))),
          Symbol("regime")(Some(RegimeTag("sa"))),
          Symbol("template")(Some(TemplateTag("SA_316")))
        )
        event3 must have(
          Symbol("eventType")(Opened),
          Symbol("emailAddress")(EmailAddress("test-198@test.com")),
          Symbol("detected")(Instant.parse("2014-04-04T16:34:54.000Z")),
          Symbol("code")(Some(698)),
          Symbol("mailgunId")(Some(MailgunId("3@example.com"))),
          Symbol("regime")(Some(RegimeTag("paye"))),
          Symbol("template")(None)
        )
        next must be(
          "http://localhost:10029/v3/domain/events/YmVnaW4lM0RUdWUlMkMrMjUrTWFyKzIwMTQrMTclM0EzOCUzQTEzKyUyQjAwMDAmc2tpcCUzRDEwMA"
        )
      }
    }
  }

  "Converting an email to a form body" must {
    val sampleMsg = EmailMessage(
      from = "test@email.com",
      to = List(EmailAddress("toAddress@email.com"), EmailAddress("toAddress2@email.com")),
      replyToAddress = Some(EmailAddress("replyToAddress@email.com")),
      subject = "object",
      plainTextBody = "plain body",
      htmlBody = "html body",
      templateId = "newMessageAlert",
      templateRegime = "tamc"
    )

    def theEmailAsAFormBody(msg: EmailMessage = sampleMsg) =
      mcf.emailToFormBody(msg)

    "always include a from address" in {
      theEmailAsAFormBody() must contain("from" -> Seq("test@email.com"))
    }

    "always include an o:tag for sa" in {
      theEmailAsAFormBody() must contain("o:tag[0]" -> Seq("regime.tamc"))
    }

    "always include an o:tag for templateId" in {
      theEmailAsAFormBody() must contain("o:tag[1]" -> Seq("template.newMessageAlert"))
    }

    "always include an o:tag for outgoing messages identification purposes" in {
      theEmailAsAFormBody() must contain("o:tag[2]" -> Seq("mdtp"))
    }

    "include all the to email addresses" in {
      theEmailAsAFormBody() must contain("to" -> Seq("toAddress@email.com,toAddress2@email.com"))
    }

    "include the subject" in {
      theEmailAsAFormBody() must contain("subject" -> Seq("object"))
    }

    "include the plain body if present" in {
      theEmailAsAFormBody() must contain("text" -> Seq("plain body"))
    }

    "include the html body if present" in {
      theEmailAsAFormBody() must contain("html" -> Seq("html body"))
    }

    "include the to field even if empty" in {
      theEmailAsAFormBody(sampleMsg.copy(to = Nil)) must contain("to" -> Seq(""))
    }

    "include a reply-to address" in {
      theEmailAsAFormBody() must contain("h:reply-to" -> Seq("replyToAddress@email.com"))
    }

    "exclude a reply-to address if empty" in {
      theEmailAsAFormBody(sampleMsg.copy(replyToAddress = None))
        .contains("h:reply-to") mustBe false
    }

    "mark every message as needing open tracking" in {
      theEmailAsAFormBody() must contain("o:tracking-opens" -> Seq("yes"))
    }

    "include v:enrolment for enrolment if encryption value present" in {
      theEmailAsAFormBody(sampleMsg.copy(tags = Map("enrolment" -> "encryptedValue"))) must contain(
        "v:enrolment" -> Seq("encryptedValue")
      )
    }

    "not include v:enrolment for enrolment if there is no encryption value" in {
      theEmailAsAFormBody(sampleMsg) must not contain ("v:enrolment" -> Seq("encryptedValue"))
    }

    "include v:messageId for messageId if encryption value present" in {
      theEmailAsAFormBody(sampleMsg.copy(tags = Map("messageId" -> "encryptedValue"))) must contain(
        "v:messageId" -> Seq("encryptedValue")
      )
    }

    "not include v:messageId for messageId if there is no encryption value" in {
      theEmailAsAFormBody(sampleMsg) must not contain ("v:messageId" -> Seq("encryptedValue"))
    }

    "include v:messageSource for source if encryption value present" in {
      theEmailAsAFormBody(sampleMsg.copy(tags = Map("source" -> "encryptedValue"))) must contain(
        "v:source" -> Seq("encryptedValue")
      )
    }

    "not include v:messageSource for source if there is no encryption value" in {
      theEmailAsAFormBody(sampleMsg) must not contain ("v:source" -> Seq("encryptedValue"))
    }
  }

  "Reading bounces with malformed email address" must {
    "work for a single item" in {
      val sampleJson = Json.parse("""
                                    |{
                                    |  "id" : "OzfuBvicSJCijL6mBxgTwg",
                                    |  "event" : "failed",
                                    |  "recipient": "test-200\\@test.com",
                                    |  "timestamp": 1396629292,
                                    |  "delivery-status": {
                                    |    "code": 700
                                    |  },
                                    |  "message": {
                                    |    "headers": {
                                    |      "message-id": "77AF5C3CA1416D93FC47AF8AD42A60AD@example.com"
                                    |    }
                                    |  },
                                    |  "user-variables":{},
                                    |  "tags":[]
                                    |}
        """.stripMargin)
      val event =
        sampleJson.validate[Option[MailgunEvent]](mcf.readMailgunEvent)
      event.get must be(None)
    }

    "work for a sequence of items" in {
      val sampleJson = Json.parse(
        """
          |{
          |  "items": [
          |    {
          |      "id" : "OzfuBvicSJCijL6mBxgTwg",
          |      "event" : "failed",
          |      "recipient": "test-200@test.com",
          |      "timestamp": 1396629292,
          |      "delivery-status": {
          |        "code": 700
          |      },
          |     "message": {
          |       "headers": {
          |         "message-id": "1@example.com"
          |       }
          |     },
          |     "user-variables":{},
          |     "tags":[]
          |    },
          |    {
          |      "id" : "OzfuBvicSJCijL6mBxgTwa",
          |      "event" : "failed",
          |      "severity": "permanent",
          |      "recipient": "test-199@test.com",
          |      "timestamp": 1396629293,
          |      "delivery-status": {
          |        "code": 699
          |      },
          |      "message": {
          |         "headers": {
          |           "message-id": "2@example.com"
          |         }
          |       },
          |       "user-variables":{},
          |       "tags":[]
          |    },
          |    {
          |      "id" : "OzfuBvicSJCijL6mBxgTwb",
          |      "event" : "opened",
          |      "recipient": "test-198\\@test.com",
          |      "timestamp": 1396629294,
          |      "delivery-status": {
          |        "code": 698
          |      },
          |      "message": {
          |         "headers": {
          |           "message-id": "3@example.com"
          |         }
          |       },
          |       "user-variables":{},
          |       "tags":[]
          |    }
          |  ],
          |  "paging": {
          |    "next": "http:\/\/localhost:10029\/v3\/domain\/events\/YmVnaW4lM0RUdWUlMkMrMjUrTWFyKzIwMTQrMTclM0EzOCUzQTEzKyUyQjAwMDAmc2tpcCUzRDEwMA",
          |    "previous": "http:\/\/localhost:10029\/v3\/domain\/events\/previous"
          |  }
          |}
      """.stripMargin
      )
      val events = sampleJson.validate[EventsPage](mcf.readEventsAndNextPage)

      events.get.asInstanceOf[PageWithEvents].events.size must be(2)

      inside(events) { case JsSuccess(PageWithEvents(Seq(event1, event2), next), _) =>
        event1 must have(
          Symbol("eventType")(TemporaryBounce),
          Symbol("emailAddress")(EmailAddress("test-200@test.com")),
          Symbol("detected")(Instant.parse("2014-04-04T16:34:52.000Z")),
          Symbol("code")(Some(700)),
          Symbol("mailgunId")(Some(MailgunId("1@example.com")))
        )
        event2 must have(
          Symbol("eventType")(PermanentBounce),
          Symbol("emailAddress")(EmailAddress("test-199@test.com")),
          Symbol("detected")(Instant.parse("2014-04-04T16:34:53.000Z")),
          Symbol("code")(Some(699)),
          Symbol("mailgunId")(Some(MailgunId("2@example.com")))
        )
        next must be(
          "http://localhost:10029/v3/domain/events/YmVnaW4lM0RUdWUlMkMrMjUrTWFyKzIwMTQrMTclM0EzOCUzQTEzKyUyQjAwMDAmc2tpcCUzRDEwMA"
        )
      }
    }

  }
}

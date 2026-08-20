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

package uk.gov.hmrc.email.connectors

import cats.syntax.either.*
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.libs.json.{ JsNull, JsValue, Json }
import play.api.test.Helpers.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.mailgun.{ EventsPage, PageWithEvents }
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.model.EventType.*
import uk.gov.hmrc.email.util.LogCapturing
import uk.gov.hmrc.email.utils.NonEmptyString
import uk.gov.hmrc.email.{ FakeSenderDomainConfiguration, SpecBase }
import uk.gov.hmrc.http.client.{ HttpClientV2, RequestBuilder }
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse, UpstreamErrorResponse }
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source

case class Mail(value: String) {
  override def toString: String = value.stripPrefix("<").stripSuffix(">")
}

class MailgunConnectorSpec extends SpecBase with ScalaFutures with LogCapturing {
  type Hdrs = Seq[(String, String)]
  type QryParams = Seq[(String, String)]
  type Body = Map[String, Seq[String]]

  val actorSystem: ActorSystem = ActorSystem("EventEmitterSpec")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)

  "When deleting bounces, the MailgunConnector" should {
    val validEmailAddress = EmailAddress("email@address.com")

    "return true if deleting the bounce succeeds" in new TestCase {

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(OK, JsNull, Map.empty).asRight))

      mailgunConnector
        .deleteBouncesFor(validEmailAddress)
        .futureValue mustBe true
    }
    "return false if deleting the bounce results in a 404" in new TestCase {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(UpstreamErrorResponse("The princess is in the other castle", 404, 404).asLeft))

      mailgunConnector
        .deleteBouncesFor(validEmailAddress)
        .futureValue mustBe false
    }

    "propagate any other exceptions up to the caller" in new TestCase {
      private val runtimeException = new RuntimeException
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(Future.failed(runtimeException))

      mailgunConnector
        .deleteBouncesFor(validEmailAddress)
        .failed
        .futureValue must be(runtimeException)
    }
  }

  "When sending email, the MailgunConnector" should {
    "return a message id" in new TestCase {
      private val expected =
        MailgunSendResponse(id = MailgunId(UUID.randomUUID().toString), message = "message")
      private val response = HttpResponse(status = OK, json = Json.toJson(expected), headers = Map.empty)
      when(requestBuilder.execute[HttpResponse](using any, any)).thenReturn(Future.successful(response))
      mailgunConnector
        .send(EmailMessage("", Nil, None, "", "", "", "", "generic"))
        .futureValue mustBe expected
    }
  }

  "When validating an email address the MailgunConnector" should {

    "return true when Mailgun responds that email address is valid" in new TestCase {
      private val response = Json.parse("""
                                          |{
                                          |    "address": "rob.walpole@gmail.com",
                                          |    "did_you_mean": null,
                                          |    "is_disposable_address": false,
                                          |    "is_role_address": false,
                                          |    "is_valid": true,
                                          |    "mailbox_verification": "unknown",
                                          |    "parts": {
                                          |        "display_name": null,
                                          |        "domain": "gmail.com",
                                          |        "local_part": "rob.walpole"
                                          |    },
                                          |    "reason": null
                                          |}
                                          |""".stripMargin)

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(OK, response, Map.empty)))

      val str: NonEmptyString = NonEmptyString.validate("rob.walpole@gmail.con") match {
        case Right(r) => r
        case _        => fail("NonEmptyString.validate failed")
      }
      private val result = mailgunConnector.validate(str).futureValue
      result mustBe true
    }

    "return false when Mailgun responds that email address is invalid" in new TestCase {
      private val responseJson: JsValue =
        Json.parse("""
                     |{
                     |    "address": "rob.walpole@gmail.con",
                     |    "did_you_mean": "rob.walpole@gmail.com",
                     |    "is_disposable_address": false,
                     |    "is_role_address": false,
                     |    "is_valid": false,
                     |    "mailbox_verification": "unknown",
                     |    "parts": {
                     |        "display_name": null,
                     |        "domain": "gmail.con",
                     |        "local_part": "rob.walpole"
                     |    },
                     |    "reason": "No MX records found for domain 'gmail.con'"
                     |}
                     |""".stripMargin)

      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(OK, responseJson, Map.empty)))

      val str: NonEmptyString = NonEmptyString.validate("rob.walpole@gmail.con") match {
        case Right(r) => r
        case _        => fail("NonEmptyString.validate failed")
      }
      private val result = mailgunConnector.validate(str).futureValue
      result mustBe false
    }

    "return true when Mailgun responds with HTTP status 413" in new TestCase {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(REQUEST_ENTITY_TOO_LARGE, JsNull, Map.empty)))
      val str: NonEmptyString = NonEmptyString.validate("rob.walpole@gmail.con") match {
        case Right(r) => r
        case _        => fail("NonEmptyString.validate failed")
      }

      private val result = mailgunConnector.validate(str).futureValue
      result mustBe true
    }

    "return false when Mailgun responds with any other HTTP status" in new TestCase {
      when(requestBuilder.execute[HttpResponse](using any, any))
        .thenReturn(Future.successful(HttpResponse(INTERNAL_SERVER_ERROR, JsNull, Map.empty)))

      val str: NonEmptyString = NonEmptyString.validate("rob.walpole@gmail.con") match {
        case Right(r) => r
        case _        => fail("NonEmptyString.validate failed")
      }
      private val result = mailgunConnector.validate(str).futureValue
      result mustBe false
    }
  }

  "When extracting an enrolment from a mailgun storage location with the getEnrolmentFromStorage(url) method" should {

    "body-plain" must {
      "read enrolment if there are multiple values in json object" in {
        val bodyPlain =
          "Delivery is delayed to these recipients or groups:\r\n\r\nlisa.mably@morganplc.com<mailto:lisa.mably@morganplc.com>\r\n\r\nSubject: [--EXTERNAL--]HMRC Duty Deferred Direct Debit: advance notice of payment\r\n\r\nThis message hasn't been delivered yet. Delivery will continue to be attempted.\r\n\r\nThe server will keep trying to deliver this message for the next 1 days, 19 hours and 56 minutes. You'll be notified if the message can't be delivered by that time.\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\nDiagnostic information for administrators:\r\n\r\nGenerating server: UKLOLONEX02M.MorganGroupPlc.com\r\nReceiving server: mdb27 (172.24.16.149)\r\n\r\nlisa.mably@morganplc.com\r\nRemote Server at mdb27 (172.24.16.149) returned '400 4.4.7 Message delayed'\r\n11/01/2023 04:20:14 - Remote Server at mdb27 (172.24.16.149) returned '441 4.4.1 Error encountered while communicating with primary target IP address: \"Failed to connect. Winsock error code: 10060, Win32 error code: 10060.\" Attempted failover to alternate host, but that did not succeed. Either there are no alternate hosts, or delivery failed to all alternate hosts. The last endpoint attempted was 172.24.16.149:475'\r\n\r\nOriginal message headers:\r\n\r\nReceived: from UKLOLONEX01M.MorganGroupPlc.com (172.24.16.146) by\r\n UKLOLONEX02M.MorganGroupPlc.com (172.24.16.147) with Microsoft SMTP Server\r\n (TLS) id 15.0.1497.42; Wed, 11 Jan 2023 00:21:24 +0000\r\nReceived: from eu-smtp-1.mimecast.com (91.220.42.227) by\r\n UKLOLONEX01M.MorganGroupPlc.com (172.24.16.146) with Microsoft SMTP Server\r\n (TLS) id 15.0.1497.42 via Frontend Transport; Wed, 11 Jan 2023 00:21:23 +0000\r\nARC-Message-Signature: i=1; a=rsa-sha256; c=relaxed/relaxed;\r\n        d=dkim.mimecast.com; s=201903; t=1673396484;\r\n        h=from:from:sender:sender:reply-to:subject:subject:date:date:\r\n         message-id:message-id:to:to:cc:mime-version:mime-version:\r\n         content-type:content-type:dkim-signature;\r\n        bh=ywcENFZWnkt8KZar652jcEyrhW0Q53UQn/f2cF6K1t8=;\r\n        b=Kirz2dzCvftzRxcwwUZ9IfrMjipGczp77MpQhmTz922pN/BwHkIEkIQbDe5BdxaGaPwj6v\r\n        dkX8QIKVDMXP/LPoJGvpLVE4C1F+MCBPvCb/oWbisJnkz/iMbYuEjPoxkHycDgrl0Ks4D/\r\n        XgQqvxvzFmWTy+3e6LEfDA4N0w1hBOJ0sRET8F1UTkQnL+tm2BMwlxPgNh41T7PyR4uMzB\r\n        iR8A4okkFJTuRd7rSheJ3OejM4rS638nQ0CHyK9+UCliLJGjt/cr5SOFzRZz7B85+pAeiy\r\n        bexhKTIyveuiE3sJkSWAuporDEFt/o52AtM+Q3mIjhk3RFlHJh62cjQJ+4qGug==\r\nARC-Seal: i=1; s=201903; d=dkim.mimecast.com; t=1673396484; a=rsa-sha256;\r\n        cv=none;\r\n        b=eY5VGFf/BW+o0KaprTayY19vAwICpmuP2o/66YkGPJMDegEWVeFnn/kfhhQhEIDhyWVUw0\r\n        KEAuf4o9jQze7MF3NYioUZXiRu9nkp+uWfvtv7ArlZ26MOW2+GpBvd18dpiAyYqwjA6nAG\r\n        DUFQAoNqC6tIJiAWim9ngIkLdjSLmGXtxrnOV5DoNr8UILRULje1TlRmdWHuZqmR2JSeF5\r\n        FLYBYH18sObv38uAnjDzHVUbvkj3RKEc6ey4Ih0OkHmsBw67iZz+uDagMUKxPPu0SBh1dR\r\n        aNPY8093iaRPYTuCdls/ZfjPj6386B9uVd7ao5bj8/XS+/0a5rt3cH30c0zpUw==\r\nARC-Authentication-Results: i=1;\r\n        relay.mimecast.com;\r\n        dkim=pass header.d=tax.service.gov.uk header.s=k1 header.b=aP43Eiif;\r\n        dmarc=pass (policy=reject) header.from=tax.service.gov.uk;\r\n        spf=pass (relay.mimecast.com: domain of \"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\" designates 198.61.254.23 as permitted sender) smtp.mailfrom=\"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\"\r\nReceived: from so254-23.mailgun.net (so254-23.mailgun.net [198.61.254.23])\r\n by relay.mimecast.com with ESMTP with STARTTLS (version=TLSv1.3,\r\n cipher=TLS_AES_128_GCM_SHA256) id uk-mta-242-n3K7JCDMM3ayfzsasdVzHg-2; Tue,\r\n 10 Jan 2023 04:19:56 +0000\r\nX-MC-Unique: n3K7JCDMM3ayfzsasdVzHg-2\r\nDKIM-Signature: a=rsa-sha256; v=1; c=relaxed/relaxed; d=tax.service.gov.uk; q=dns/txt;\r\n s=k1; t=1673324395; x=1673331595; h=Message-Id: To: To: From: From:\r\n Subject: Subject: Content-Type: Mime-Version: Date: Sender: Sender;\r\n bh=n4REY3xbR1BdZ91S/eQSr76rf3jx6tYA1dtgulYADBs=; b=aP43Eiif379qv2miRiio3NZuR+atmeqlnoSu7Fnv07UZi5RtkhBmtKA0uqqie8VR5J9IcMjL\r\n GtKD8fhOBSgffwDCrPMAUEZglTAG7wzNOJpL6WTeMwxT5IJQk0+hDoymQjZIIIMMPBqGz7as\r\n mZ/Bht//HB/fltmERwge5EpU5KI=\r\nX-Mailgun-Sending-Ip: 198.61.254.23\r\nX-Mailgun-Sid: WyIxYTBjYSIsImxpc2EubWFibHlAbW9yZ2FucGxjLmNvbSIsImVjNDYiXQ==\r\nReceived: from <unknown> (<unknown> []) by 29ce08e034c2 with HTTP id\r\n 63bce76b326547212adbecf9; Tue, 10 Jan 2023 04:19:55 GMT\r\nSender: <noreply@tax.service.gov.uk>\r\nDate: Tue, 10 Jan 2023 04:19:55 +0000\r\nMIME-Version: 1.0\r\nSubject: [--EXTERNAL--]HMRC Duty Deferred Direct Debit: advance notice of\r\n payment\r\nFrom: HMRC Direct Debit <noreply@tax.service.gov.uk>\r\nTo: <lisa.mably@morganplc.com>\r\nX-Mailgun-Tag: mdtp\r\nX-Mailgun-Tag: regime.online-payment-service\r\nX-Mailgun-Tag: template.cds_ddi_reminder_dcs_alert\r\nX-Mailgun-Track-Opens: true\r\nX-Mailgun-Variables: {\"enrolment\": \"test2\",\r\n \"messageId\":\r\n \"bpwHQvFoIVee0WeniFzkDyDn9rxXBaNje+oRAEutx7rLtU/6olW6TlcQp6PBO5J3\",\r\n \"source\": \"4PRiqswj7rqtjhES3PWcdg==\"}\r\nMessage-ID: <20230110041955.a5a00aa6080416be@tax.service.gov.uk>\r\nAuthentication-Results: relay.mimecast.com;\r\n        dkim=pass header.d=tax.service.gov.uk header.s=k1 header.b=aP43Eiif;\r\n        dmarc=pass (policy=reject) header.from=tax.service.gov.uk;\r\n        spf=pass (relay.mimecast.com: domain of \"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\" designates 198.61.254.23 as permitted sender) smtp.mailfrom=\"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\"\r\nX-Mimecast-Spam-Score: 1\r\nX-Mimecast-Impersonation-Protect: Policy=VIP Impersonation Protection Definition;Similar Internal Domain=false;Similar Monitored External Domain=false;Custom External Domain=false;Mimecast External Domain=false;Newly Observed Domain=false;Internal User Name=false;Custom Display Name List=false;Reply-to Address Mismatch=false;Targeted Threat Dictionary=false;Mimecast Threat Dictionary=false;Custom Threat Dictionary=false\r\nX-Mimecast-Impersonation-Protect: Policy=Default Impersonation Protect Definition;Similar Internal Domain=false;Similar Monitored External Domain=false;Custom External Domain=false;Mimecast External Domain=false;Newly Observed Domain=false;Internal User Name=false;Custom Display Name List=false;Reply-to Address Mismatch=false;Targeted Threat Dictionary=true;Mimecast Threat Dictionary=true;Custom Threat Dictionary=false\r\nContent-Type: multipart/alternative;\r\n        boundary=\"47768ce65b8f1f866d123619e990b4f684ab7f789c2ed204bb4b163ad7ed\"\r\nReturn-Path: bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\r\n\r\n"
        val lines = bodyPlain.split("\n").toList.map(_.trim)
        val enrolment = lines.find(_.startsWith("X-Mailgun-Variables:")) match {
          case None =>
            None
          case Some(variable) =>
            val replaceComma = variable.replace(",", "}")
            val enrolment =
              (Json.parse(replaceComma.stripPrefix("X-Mailgun-Variables:").trim) \ "enrolment").asOpt[String]
            enrolment
        }
        enrolment mustBe Some("test2")
      }

      "read enrolment with single  enrolment value in json object" in {
        val bodyPlain =
          "Delivery is delayed to these recipients or groups:\r\n\r\nlisa.mably@morganplc.com<mailto:lisa.mably@morganplc.com>\r\n\r\nSubject: [--EXTERNAL--]HMRC Duty Deferred Direct Debit: advance notice of payment\r\n\r\nThis message hasn't been delivered yet. Delivery will continue to be attempted.\r\n\r\nThe server will keep trying to deliver this message for the next 1 days, 19 hours and 56 minutes. You'll be notified if the message can't be delivered by that time.\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\nDiagnostic information for administrators:\r\n\r\nGenerating server: UKLOLONEX02M.MorganGroupPlc.com\r\nReceiving server: mdb27 (172.24.16.149)\r\n\r\nlisa.mably@morganplc.com\r\nRemote Server at mdb27 (172.24.16.149) returned '400 4.4.7 Message delayed'\r\n11/01/2023 04:20:14 - Remote Server at mdb27 (172.24.16.149) returned '441 4.4.1 Error encountered while communicating with primary target IP address: \"Failed to connect. Winsock error code: 10060, Win32 error code: 10060.\" Attempted failover to alternate host, but that did not succeed. Either there are no alternate hosts, or delivery failed to all alternate hosts. The last endpoint attempted was 172.24.16.149:475'\r\n\r\nOriginal message headers:\r\n\r\nReceived: from UKLOLONEX01M.MorganGroupPlc.com (172.24.16.146) by\r\n UKLOLONEX02M.MorganGroupPlc.com (172.24.16.147) with Microsoft SMTP Server\r\n (TLS) id 15.0.1497.42; Wed, 11 Jan 2023 00:21:24 +0000\r\nReceived: from eu-smtp-1.mimecast.com (91.220.42.227) by\r\n UKLOLONEX01M.MorganGroupPlc.com (172.24.16.146) with Microsoft SMTP Server\r\n (TLS) id 15.0.1497.42 via Frontend Transport; Wed, 11 Jan 2023 00:21:23 +0000\r\nARC-Message-Signature: i=1; a=rsa-sha256; c=relaxed/relaxed;\r\n        d=dkim.mimecast.com; s=201903; t=1673396484;\r\n        h=from:from:sender:sender:reply-to:subject:subject:date:date:\r\n         message-id:message-id:to:to:cc:mime-version:mime-version:\r\n         content-type:content-type:dkim-signature;\r\n        bh=ywcENFZWnkt8KZar652jcEyrhW0Q53UQn/f2cF6K1t8=;\r\n        b=Kirz2dzCvftzRxcwwUZ9IfrMjipGczp77MpQhmTz922pN/BwHkIEkIQbDe5BdxaGaPwj6v\r\n        dkX8QIKVDMXP/LPoJGvpLVE4C1F+MCBPvCb/oWbisJnkz/iMbYuEjPoxkHycDgrl0Ks4D/\r\n        XgQqvxvzFmWTy+3e6LEfDA4N0w1hBOJ0sRET8F1UTkQnL+tm2BMwlxPgNh41T7PyR4uMzB\r\n        iR8A4okkFJTuRd7rSheJ3OejM4rS638nQ0CHyK9+UCliLJGjt/cr5SOFzRZz7B85+pAeiy\r\n        bexhKTIyveuiE3sJkSWAuporDEFt/o52AtM+Q3mIjhk3RFlHJh62cjQJ+4qGug==\r\nARC-Seal: i=1; s=201903; d=dkim.mimecast.com; t=1673396484; a=rsa-sha256;\r\n        cv=none;\r\n        b=eY5VGFf/BW+o0KaprTayY19vAwICpmuP2o/66YkGPJMDegEWVeFnn/kfhhQhEIDhyWVUw0\r\n        KEAuf4o9jQze7MF3NYioUZXiRu9nkp+uWfvtv7ArlZ26MOW2+GpBvd18dpiAyYqwjA6nAG\r\n        DUFQAoNqC6tIJiAWim9ngIkLdjSLmGXtxrnOV5DoNr8UILRULje1TlRmdWHuZqmR2JSeF5\r\n        FLYBYH18sObv38uAnjDzHVUbvkj3RKEc6ey4Ih0OkHmsBw67iZz+uDagMUKxPPu0SBh1dR\r\n        aNPY8093iaRPYTuCdls/ZfjPj6386B9uVd7ao5bj8/XS+/0a5rt3cH30c0zpUw==\r\nARC-Authentication-Results: i=1;\r\n        relay.mimecast.com;\r\n        dkim=pass header.d=tax.service.gov.uk header.s=k1 header.b=aP43Eiif;\r\n        dmarc=pass (policy=reject) header.from=tax.service.gov.uk;\r\n        spf=pass (relay.mimecast.com: domain of \"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\" designates 198.61.254.23 as permitted sender) smtp.mailfrom=\"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\"\r\nReceived: from so254-23.mailgun.net (so254-23.mailgun.net [198.61.254.23])\r\n by relay.mimecast.com with ESMTP with STARTTLS (version=TLSv1.3,\r\n cipher=TLS_AES_128_GCM_SHA256) id uk-mta-242-n3K7JCDMM3ayfzsasdVzHg-2; Tue,\r\n 10 Jan 2023 04:19:56 +0000\r\nX-MC-Unique: n3K7JCDMM3ayfzsasdVzHg-2\r\nDKIM-Signature: a=rsa-sha256; v=1; c=relaxed/relaxed; d=tax.service.gov.uk; q=dns/txt;\r\n s=k1; t=1673324395; x=1673331595; h=Message-Id: To: To: From: From:\r\n Subject: Subject: Content-Type: Mime-Version: Date: Sender: Sender;\r\n bh=n4REY3xbR1BdZ91S/eQSr76rf3jx6tYA1dtgulYADBs=; b=aP43Eiif379qv2miRiio3NZuR+atmeqlnoSu7Fnv07UZi5RtkhBmtKA0uqqie8VR5J9IcMjL\r\n GtKD8fhOBSgffwDCrPMAUEZglTAG7wzNOJpL6WTeMwxT5IJQk0+hDoymQjZIIIMMPBqGz7as\r\n mZ/Bht//HB/fltmERwge5EpU5KI=\r\nX-Mailgun-Sending-Ip: 198.61.254.23\r\nX-Mailgun-Sid: WyIxYTBjYSIsImxpc2EubWFibHlAbW9yZ2FucGxjLmNvbSIsImVjNDYiXQ==\r\nReceived: from <unknown> (<unknown> []) by 29ce08e034c2 with HTTP id\r\n 63bce76b326547212adbecf9; Tue, 10 Jan 2023 04:19:55 GMT\r\nSender: <noreply@tax.service.gov.uk>\r\nDate: Tue, 10 Jan 2023 04:19:55 +0000\r\nMIME-Version: 1.0\r\nSubject: [--EXTERNAL--]HMRC Duty Deferred Direct Debit: advance notice of\r\n payment\r\nFrom: HMRC Direct Debit <noreply@tax.service.gov.uk>\r\nTo: <lisa.mably@morganplc.com>\r\nX-Mailgun-Tag: mdtp\r\nX-Mailgun-Tag: regime.online-payment-service\r\nX-Mailgun-Tag: template.cds_ddi_reminder_dcs_alert\r\nX-Mailgun-Track-Opens: true\r\nX-Mailgun-Variables: {\"enrolment\": \"test2\"}\r\nMessage-ID: <20230110041955.a5a00aa6080416be@tax.service.gov.uk>\r\nAuthentication-Results: relay.mimecast.com;\r\n        dkim=pass header.d=tax.service.gov.uk header.s=k1 header.b=aP43Eiif;\r\n        dmarc=pass (policy=reject) header.from=tax.service.gov.uk;\r\n        spf=pass (relay.mimecast.com: domain of \"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\" designates 198.61.254.23 as permitted sender) smtp.mailfrom=\"bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\"\r\nX-Mimecast-Spam-Score: 1\r\nX-Mimecast-Impersonation-Protect: Policy=VIP Impersonation Protection Definition;Similar Internal Domain=false;Similar Monitored External Domain=false;Custom External Domain=false;Mimecast External Domain=false;Newly Observed Domain=false;Internal User Name=false;Custom Display Name List=false;Reply-to Address Mismatch=false;Targeted Threat Dictionary=false;Mimecast Threat Dictionary=false;Custom Threat Dictionary=false\r\nX-Mimecast-Impersonation-Protect: Policy=Default Impersonation Protect Definition;Similar Internal Domain=false;Similar Monitored External Domain=false;Custom External Domain=false;Mimecast External Domain=false;Newly Observed Domain=false;Internal User Name=false;Custom Display Name List=false;Reply-to Address Mismatch=false;Targeted Threat Dictionary=true;Mimecast Threat Dictionary=true;Custom Threat Dictionary=false\r\nContent-Type: multipart/alternative;\r\n        boundary=\"47768ce65b8f1f866d123619e990b4f684ab7f789c2ed204bb4b163ad7ed\"\r\nReturn-Path: bounce+cdbc1d.ec46-lisa.mably=morganplc.com@tax.service.gov.uk\r\n\r\n"
        val lines = bodyPlain.split("\n").toList.map(_.trim)

        val enrolment = lines.find(_.startsWith("X-Mailgun-Variables:")) match {
          case None =>
            None
          case Some(variable) =>
            val replaceComma = variable.replace(",", "}")
            val enrolment =
              (Json.parse(replaceComma.stripPrefix("X-Mailgun-Variables:").trim) \ "enrolment").asOpt[String]
            enrolment
        }
        enrolment mustBe Some("test2")
      }
    }
  }

  "Response body from Mailgun" should {
    "parse all events (with or without tags )" in new TestCase {
      import uk.gov.hmrc.email.mailgun.Converters.readEventsAndNextPage
      val resource = readFile("mailgun-response.json")

      val json = Json.parse(resource.mkString)
      val eventsPage = json.validate[EventsPage].get
      val pageWithEvents = eventsPage.asInstanceOf[PageWithEvents]
      pageWithEvents.events.size mustBe 3

      pageWithEvents.events.count(_.regime.isEmpty) mustBe 1
      pageWithEvents.events.count(_.regime.nonEmpty) mustBe 2
    }
  }

  trait TestCase extends FakeSenderDomainConfiguration {

    val mockHttpClient: HttpClientV2 = mock[HttpClientV2]
    val requestBuilder: RequestBuilder = mock[RequestBuilder]
    val mailgunBaseUrl: String = "http://host:8080"
    when(
      mockHttpClient.delete(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(
      mockHttpClient.post(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(
      mockHttpClient.get(any[URL])(any[HeaderCarrier])
    ).thenReturn(requestBuilder)
    when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)
    when(requestBuilder.withBody(any)(using any, any, any)).thenReturn(requestBuilder)
    when(requestBuilder.withProxy).thenReturn(requestBuilder)
    when(requestBuilder.setHeader(any)).thenReturn(requestBuilder)

    val mailgunConnector: MailgunConnector =
      new MailgunConnector(senderDomainConfiguration, mockHttpClient, mailgunBaseUrl) {}

    implicit val emptyHC: HeaderCarrier = HeaderCarrier()

    val now: Instant = Instant.now()

    def testBounceEvent(index: Int): MailgunEvent =
      MailgunEvent(
        id = "event id",
        emailAddress = EmailAddress(s"test$index@test.com"),
        detected = now.plus(index, ChronoUnit.MINUTES),
        code = Some(index),
        None,
        Some(MailgunId(s"${UUID.randomUUID()}@mailgun.com")),
        PermanentBounce,
        None,
        None,
        Map.empty,
        None,
        None
      )

    val testBounceEvent: MailgunEvent = testBounceEvent(1)

    def readFile(fileName: String): String = {
      val resource = Source.fromURL(getClass.getResource("/" + fileName))
      val resourceAsString = resource.mkString
      resource.close()
      resourceAsString
    }

    val OK = 200

  }
}

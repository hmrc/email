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

package uk.gov.hmrc.email.services

import org.apache.pekko.util.ccompat.JavaConverters.ListHasAsScala
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ times, verify, when }
import org.scalatest.LoneElement
import org.scalatest.concurrent.{ Eventually, IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import play.api.libs.json.{ JsValue, Json }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.connectors.{ ImiConnector, ImiConnectors }
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.{ ConsentItem, ConsentItemList, DeleteConsentFailed, DeleteConsentNotFound, DeleteConsentSuccess }
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }

import java.time.Instant
import java.time.Instant.*
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class SuppressionListManagementSpec
    extends SpecBase with ScalaFutures with IntegrationPatience with LoneElement with Eventually {

  "clean function" must {
    "delete suppression list items only if lastUpdated time is before expiry date" in new SetUp {
      suppressionListManagement.clean(List("hmrc", "developer")).futureValue mustBe ()
      verify(imiConnectorMock, times(2)).deleteConsent(EmailAddress("test1@digital.hmrc.gov.uk"), groupId, true)
      verify(imiConnectorMock, times(0)).deleteConsent(EmailAddress("test2@digital.hmrc.gov.uk"), groupId, true)
    }
  }

  "startClean function" must {
    "clean by calling getConsentList and deleteConsent functions" in new SetUp {
      suppressionListManagement.startClean(imiConnectorMock, "someGroupId").unsafeToFuture().futureValue
      verify(imiConnectorMock, times(1)).getConsentList(any[String](), any[Option[String]], any[Int])
      verify(imiConnectorMock, times(1)).deleteConsent(any[EmailAddress](), any[String](), any[Boolean]())
    }
    "not call delete function when there are on items" in new SetUp {
      when(imiConnectorMock.getConsentList(any[String], any[Option[String]], any[Int]))
        .thenReturn(Future.successful(ConsentItemList(List.empty[ConsentItem], None)))

      suppressionListManagement.startClean(imiConnectorMock, "someGroupId").unsafeToFuture().futureValue
      verify(imiConnectorMock, times(0)).deleteConsent(any[EmailAddress](), any[String](), any[Boolean]())
    }
  }

  "deleteItems function" must {
    "send success audit for DeleteConsentSuccess" in new SetUp {
      when(imiConnectorMock.deleteConsent(any[EmailAddress](), any[String](), any[Boolean]()))
        .thenReturn(Future.successful(DeleteConsentSuccess))

      suppressionListManagement.deleteItems(consentItems, imiConnectorMock, "someGroupId").futureValue

      verify(imiConnectorMock, times(2)).deleteConsent(any[EmailAddress](), any[String](), any[Boolean]())

      verify(auditMock, times(2)).sendEvent(eventCaptor.capture())(any[HeaderCarrier], any[ExecutionContext])
      val events: Seq[DataEvent] = eventCaptor.getAllValues.asScala.toList
      events.foreach { event =>
        event.auditType mustBe EventTypes.Succeeded
        event.detail must contain key "emailAddress"
        event.detail must not contain key
        "result"
      }
    }

    "send success audit with not_found for DeleteConsentNotFound" in new SetUp {
      when(imiConnectorMock.deleteConsent(any[EmailAddress](), any[String](), any[Boolean]()))
        .thenReturn(Future.successful(DeleteConsentNotFound))

      suppressionListManagement.deleteItems(consentItems, imiConnectorMock, "someGroupId").futureValue

      verify(auditMock, times(2)).sendEvent(eventCaptor.capture())(any[HeaderCarrier](), any[ExecutionContext]())

      val events: Seq[DataEvent] = eventCaptor.getAllValues.asScala.toList
      events.foreach { event =>
        event.auditType mustBe EventTypes.Succeeded
        event.detail("result") mustBe "not_found"
      }
    }

    "send failure audit for DeleteConsentFailed" in new SetUp {
      when(imiConnectorMock.deleteConsent(any[EmailAddress](), any[String](), any[Boolean]()))
        .thenReturn(Future.successful(DeleteConsentFailed(Some(500), "Server error")))

      suppressionListManagement.deleteItems(consentItems, imiConnectorMock, "someGroupId").futureValue

      verify(auditMock, times(2)).sendEvent(eventCaptor.capture())(any[HeaderCarrier](), any[ExecutionContext]())

      val events: Seq[DataEvent] = eventCaptor.getAllValues.asScala.toList
      events.foreach { event =>
        event.auditType mustBe EventTypes.Failed
        event.detail("error") mustBe "Server error"
        event.detail("statusCode") mustBe "500"
      }
    }

  }

  class SetUp {
    val auditMock: AuditConnector = mock[AuditConnector]
    val groupId = "123456"
    val imiConnectorsMock: ImiConnectors = mock[ImiConnectors]
    val expiryDays = 5
    def buildConfig(domainName: String, imiConnector: Boolean): (String, SenderDomainConfiguration) =
      domainName -> SenderDomainConfiguration(
        domainName,
        "hmrc-email-renderer",
        imiConnector,
        MailgunApiKeys("apiKey", "publicApiKey"),
        ImiApiConfig("somekey", "123456"),
        None,
        DefaultQueueConfiguration.apply(None, None),
        UrgentQueueConfiguration.apply(None),
        BackgroundQueueConfiguration.apply(None, None),
        BouncesConfiguration.apply(None),
        EventsConfiguration.apply(None)
      )

    val configurationMock: Configuration = mock[Configuration]

    when(configurationMock.get[Long](any[String]())(any())).thenReturn(expiryDays)

    val config: Map[String, SenderDomainConfiguration] =
      Map(buildConfig("hmrc", true), buildConfig("developer", true), buildConfig("confirmation", true))
    val senderDomainConfigurationLoaderMock: SenderDomainConfigurationLoader = mock[SenderDomainConfigurationLoader]
    when(senderDomainConfigurationLoaderMock.default).thenReturn(config)

    val lastUpdatedTime: String =
      now.truncatedTo(ChronoUnit.MILLIS).minus(java.time.Duration.ofDays(2)).toString
    val consentListJson: JsValue = Json.parse(s"""[
                                                 |    {
                                                 |        "channel": "email",
                                                 |        "address": "test1@digital.hmrc.gov.uk",
                                                 |        "consent": true,
                                                 |        "reason": "Email force requested",
                                                 |        "lastUpdated": "2023-06-27T13:33:51.914Z"
                                                 |    },
                                                 |        {
                                                 |        "channel": "email",
                                                 |        "address": "test2@digital.hmrc.gov.uk",
                                                 |        "consent": true,
                                                 |        "reason": "Email force requested",
                                                 |        "lastUpdated": "$lastUpdatedTime"
                                                 |    }

                                                 |]""".stripMargin)
    val eventCaptor: ArgumentCaptor[DataEvent] = ArgumentCaptor.forClass(classOf[DataEvent])

    val consentItems: List[ConsentItem] = consentListJson.as[List[ConsentItem]]
    val imiConnectorMock: ImiConnector = mock[ImiConnector]
    val consentItemList: ConsentItemList = ConsentItemList(consentItems, None)

    when(imiConnectorMock.getConsentList(any[String], any[Option[String]], any[Int]))
      .thenReturn(Future.successful(consentItemList))

    when(imiConnectorMock.deleteConsent(any[EmailAddress](), any[String], any[Boolean]))
      .thenReturn(Future.successful(1))
    when(imiConnectorsMock.all)
      .thenReturn(Map("hmrc" -> imiConnectorMock, "developer" -> imiConnectorMock, "confirmation" -> imiConnectorMock))
    val suppressionListManagement =
      new SuppressionListManagement(
        imiConnectorsMock,
        senderDomainConfigurationLoaderMock,
        configurationMock,
        audit = auditMock
      )

  }
}

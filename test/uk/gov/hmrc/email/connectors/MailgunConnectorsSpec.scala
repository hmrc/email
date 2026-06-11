/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.Configuration
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.services.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.audit.http.HttpAuditing
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import scala.concurrent.ExecutionContext.Implicits.global

class MailgunConnectorsSpec extends SpecBase {

  val actorSystem = ActorSystem("EventEmitterSpec")
  implicit val mat: Materializer = Materializer.createMaterializer(actorSystem)

  private val mockservicesConfig: ServicesConfig = mock[ServicesConfig]
  private val mockSenderDomainConfigurationLoader =
    mock[SenderDomainConfigurationLoader]
  private val mockActorSystem = mock[ActorSystem]
  private val mockHttpAuditing = mock[HttpAuditing]
  private val httpClient = mock[HttpClientV2]

  private val configuration = mock[Configuration]

  private val dqc = DefaultQueueConfiguration(None, None)
  private val uqc = UrgentQueueConfiguration(None)
  private val bqc = BackgroundQueueConfiguration(None, None)
  private val bouncesConf = BouncesConfiguration(None)
  private val eventsConf = EventsConfiguration(None)

  "MailgunConnectors" should {
    "create new connectors from a map of configurations" in {
      val mailgunConnectors =
        MailgunConnectors(
          mockservicesConfig,
          mockSenderDomainConfigurationLoader,
          mockActorSystem,
          httpClient,
          mockHttpAuditing,
          configuration
        )
      val conf = Map(
        "hmrc1" -> SenderDomainConfiguration(
          "tax.service.test1",
          "r1",
          false,
          MailgunApiKeys("k1", "k2"),
          ImiApiConfig("k1", "g1"),
          None,
          dqc,
          uqc,
          bqc,
          bouncesConf,
          eventsConf
        ),
        "hmrc2" -> SenderDomainConfiguration(
          "tax.service.test2",
          "r2",
          false,
          MailgunApiKeys("k3", "k4"),
          ImiApiConfig("k3", "g1"),
          None,
          dqc,
          uqc,
          bqc,
          bouncesConf,
          eventsConf
        )
      )
      when(mockSenderDomainConfigurationLoader.default).thenReturn(conf)
      when(mockservicesConfig.baseUrl(any[String])).thenReturn("testUrl")

      mailgunConnectors.all("hmrc1").senderDomainName mustBe "tax.service.test1"
      mailgunConnectors.all("hmrc1").apiKey mustBe "k1"
      mailgunConnectors.all("hmrc1").publicApiKey mustBe "k2"
      mailgunConnectors.all("hmrc1").mailgunBaseUrl mustBe "testUrl"

      mailgunConnectors.all("hmrc2").senderDomainName mustBe "tax.service.test2"
      mailgunConnectors.all("hmrc2").apiKey mustBe "k3"
      mailgunConnectors.all("hmrc2").publicApiKey mustBe "k4"
      mailgunConnectors.all("hmrc2").mailgunBaseUrl mustBe "testUrl"

    }

    "return the default connector" in {
      val mailgunConnectors =
        MailgunConnectors(
          mockservicesConfig,
          mockSenderDomainConfigurationLoader,
          mockActorSystem,
          httpClient,
          mockHttpAuditing,
          configuration
        )
      val conf = Map(
        "hmrc" -> SenderDomainConfiguration(
          "tax.service.test1",
          "r1",
          false,
          MailgunApiKeys("k1", "k2"),
          ImiApiConfig("k1", "g1"),
          None,
          dqc,
          uqc,
          bqc,
          bouncesConf,
          eventsConf
        ),
        "notHmrc" -> SenderDomainConfiguration(
          "tax.service.test2",
          "r2",
          false,
          MailgunApiKeys("k3", "k4"),
          ImiApiConfig("k3", "g3"),
          None,
          dqc,
          uqc,
          bqc,
          bouncesConf,
          eventsConf
        )
      )
      when(mockSenderDomainConfigurationLoader.default).thenReturn(conf)
      when(mockservicesConfig.baseUrl(any[String])).thenReturn("testUrl")
      mailgunConnectors.default.senderDomainName mustBe "tax.service.test1"
      mailgunConnectors.default.apiKey mustBe "k1"
      mailgunConnectors.default.publicApiKey mustBe "k2"
      mailgunConnectors.default.mailgunBaseUrl mustBe "testUrl"
    }
  }
}

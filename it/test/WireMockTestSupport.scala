/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }
import org.scalatest.concurrent.{ Eventually, IntegrationPatience }

object WireMockTestSupport extends Eventually with IntegrationPatience {

  val wireMockPort: Int = 11111
  val wireMockHost: String = "localhost"
}

trait WireMockTestSupport extends BeforeAndAfterEach with BeforeAndAfterAll {
  self: Suite =>
  import WireMockTestSupport._

  implicit lazy val wireMockServer: WireMockServer = new WireMockServer(wireMockConfig().port(wireMockPort))
  val wireMockHost = "localhost"
  lazy val wireMockBaseUrlAsString = s"http://$wireMockHost:$wireMockPort"
  WireMock.configureFor(wireMockPort)

  override def beforeEach(): Unit = WireMock.reset()

  override protected def beforeAll(): Unit = wireMockServer.start()

  override protected def afterAll(): Unit = wireMockServer.stop()

}

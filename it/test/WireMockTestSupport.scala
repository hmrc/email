/*
 * Copyright 2024 HM Revenue & Customs
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

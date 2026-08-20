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

package uk.gov.hmrc.email

import com.codahale.metrics.SharedMetricRegistries
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.inject.guice.GuiceApplicationBuilder

class SpecBase extends PlaySpec with BeforeAndAfterEach {
  override protected def beforeEach(): Unit =
    SharedMetricRegistries.clear()

  override protected def afterEach(): Unit =
    SharedMetricRegistries.clear()

  lazy val applicationBuilder: GuiceApplicationBuilder = new GuiceApplicationBuilder()
}

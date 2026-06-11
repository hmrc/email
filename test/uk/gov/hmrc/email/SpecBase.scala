/*
 * Copyright 2023 HM Revenue & Customs
 *
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

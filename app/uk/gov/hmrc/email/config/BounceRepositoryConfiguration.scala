/*
 * Copyright 2023 HM Revenue & Customs
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

package uk.gov.hmrc.email.config

import com.typesafe.config.Config
import javax.inject.{ Inject, Singleton }
import play.api.Configuration
import scala.concurrent.duration.{ Duration, SECONDS }

final case class BounceRepositoryConfiguration(expiry: Duration)

@Singleton
class BounceRepositoryConfigurationLoader @Inject() (configuration: Configuration) {

  def load(configuration: Config): BounceRepositoryConfiguration = {
    val bounceExpiry: Duration = Duration(configuration.getDuration(s"bounce.expiry").getSeconds, SECONDS)
    BounceRepositoryConfiguration(bounceExpiry)
  }

  lazy val default: BounceRepositoryConfiguration =
    load(configuration.underlying)

}

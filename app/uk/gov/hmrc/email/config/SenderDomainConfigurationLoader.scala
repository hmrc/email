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

import play.api.Configuration
import uk.gov.hmrc.clusterworkthrottling.Rate
import uk.gov.hmrc.email.model.EventType
import uk.gov.hmrc.email.services.*
import javax.inject.{ Inject, Singleton }
import scala.util.Try

@Singleton
class SenderDomainConfigurationLoader @Inject() (configuration: Configuration) {

  def load(config: Configuration): Map[String, SenderDomainConfiguration] = {

    lazy val senderDomainsConfig = config.get[Configuration]("senderDomains")
    senderDomainsConfig.subKeys.map { domain =>
      domain -> senderDomainConfiguration(senderDomainsConfig.get[Configuration](domain))
    }.toMap
  }

  private def senderDomainConfiguration(domainConfiguration: Configuration) = SenderDomainConfiguration(
    name = domainConfiguration.get[String]("name"),
    renderer = domainConfiguration.get[String]("renderer"),
    imiConnector = domainConfiguration.get[Boolean]("imiConnector"),
    mailgun = MailgunApiKeys(
      apiKey = domainConfiguration.get[String]("mailgun.apiKey"),
      publicApiKey = domainConfiguration.get[String]("mailgun.publicApiKey")
    ),
    imi = ImiApiConfig(
      apiKey = domainConfiguration.get[String]("imi.apiKey"),
      groupId = domainConfiguration.get[String]("imi.groupId")
    ),
    holdList = Try(domainConfiguration.get[Seq[String]]("holdList").toList).toOption,
    defaultQueue = DefaultQueueConfiguration(
      collection = domainConfiguration.getOptional[String]("defaultQueue.collection"),
      rate = domainConfiguration.getOptional[String]("defaultQueue.rate").map(Rate.parse)
    ),
    urgentQueue = UrgentQueueConfiguration(
      collection = domainConfiguration.getOptional[String]("urgentQueue.collection")
    ),
    backgroundQueue = BackgroundQueueConfiguration(
      collection = domainConfiguration.getOptional[String]("backgroundQueue.collection"),
      rate = domainConfiguration.getOptional[String]("backgroundQueue.rate").map(Rate.parse)
    ),
    bounces = BouncesConfiguration(
      collection = domainConfiguration.getOptional[String]("bounces.collection")
    ),
    events = EventsConfiguration(
      collection = domainConfiguration.getOptional[String]("events.collection")
    )
  )

  lazy val default: Map[String, SenderDomainConfiguration] = load(configuration)

  lazy val metricConfig: List[(String, String)] = {
    for {
      domain <- default.values.map(_.name)
      event  <- EventType.values.map(_.name)
    } yield (domain, event)
  }.toList

}

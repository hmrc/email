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

package uk.gov.hmrc.email.services

import play.api.libs.json.{ JsError, JsString, JsSuccess, Reads }
import uk.gov.hmrc.clusterworkthrottling.Rate
import uk.gov.hmrc.email.services.Priority.Priority
import scala.util.{ Success, Try }

object Priority extends Enumeration {
  type Priority = Value
  val standard, urgent, background, default, priority = Value

  def isDefault(s: String): Boolean =
    Try(Priority.withName(s)) match {
      case Success(Priority.default) => true
      case _                         => false
    }

  def isPriority(s: String): Boolean =
    Try(Priority.withName(s)) match {
      case Success(Priority.priority) => true
      case _                          => false
    }

  def isBackground(s: String): Boolean =
    Try(Priority.withName(s)) match {
      case Success(Priority.background) => true
      case _                            => false
    }

  def isDefault(p: Option[Priority]): Boolean = p.contains(standard)

  def isPriority(p: Option[Priority]): Boolean = p.contains(urgent)

  def isBackground(p: Option[Priority]): Boolean = p.contains(background)

  implicit val priorityReads: Reads[Priority] = Reads[Priority] {
    case JsString(s) =>
      Try(JsSuccess(Priority.withName(s)))
        .getOrElse(JsError(s"invalid priority: $s"))
    case s => JsError(s"unexpected json [$s]")
  }
}

final case class OutboxConfig(name: String, priority: Priority, rate: Option[Rate])

final case class RouterConfiguration(defaultOutbox: OutboxConfig, otherOutboxes: Set[OutboxConfig])

final case class MailgunApiKeys(apiKey: String, publicApiKey: String)

final case class ImiApiConfig(apiKey: String, groupId: String)

final case class SenderDomainConfiguration(
  name: String,
  renderer: String,
  imiConnector: Boolean,
  mailgun: MailgunApiKeys,
  imi: ImiApiConfig,
  holdList: Option[List[String]],
  defaultQueue: DefaultQueueConfiguration,
  urgentQueue: UrgentQueueConfiguration,
  backgroundQueue: BackgroundQueueConfiguration,
  bounces: BouncesConfiguration,
  events: EventsConfiguration
)

final case class SenderDomainDefaultsConfiguration(doNotUseInProductionEmailDomainAllowList: Option[List[String]])

trait QueueConfiguration {
  def collection: Option[String]

  def rate: Option[Rate]

  def name: String
}

case class DefaultQueueConfiguration(collection: Option[String], rate: Option[Rate]) extends QueueConfiguration {
  override def name: String = "defaultQueue"
}

// PriorityQueueConfiguration
case class UrgentQueueConfiguration(collection: Option[String]) extends QueueConfiguration {
  // "priorityQueue"
  override def name: String = "urgentQueue"
  override def rate: Option[Rate] = None
}

case class BackgroundQueueConfiguration(collection: Option[String], rate: Option[Rate]) extends QueueConfiguration {
  override def name: String = "backgroundQueue"
}

case class BouncesConfiguration(collection: Option[String])

case class EventsConfiguration(collection: Option[String])

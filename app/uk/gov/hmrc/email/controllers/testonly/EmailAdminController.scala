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

package uk.gov.hmrc.email.controllers.testonly

import org.apache.pekko.stream.Materializer
import org.mongodb.scala.result.DeleteResult
import play.api.libs.json.*
import play.api.mvc.{ Action, AnyContent, ControllerComponents }
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.email.connectors.EventEmitter
import uk.gov.hmrc.email.model.EmailQueueProcessingResults
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EventHubRepository }
import uk.gov.hmrc.email.scheduled.EventEmitterConfig
import uk.gov.hmrc.email.services.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.email.repositories.EventHubItem.workItemFormat
import uk.gov.hmrc.http.client.HttpClientV2
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }

@Singleton
class EmailAdminController @Inject() (
  routers: Routers,
  configuration: Configuration,
  httpClient: HttpClientV2,
  emailEventsRepository: EmailEventsRepository,
  eventHubRepository: EventHubRepository,
  cc: ControllerComponents
)(implicit ec: ExecutionContext, materializer: Materializer)
    extends BackendController(cc) with Logging {

  private val default: String = "hmrc"

  private val eventEmitterConfig: EventEmitterConfig =
    EventEmitterConfig(
      configuration.get[String]("eventUrlMapping.regex"),
      configuration.get[String]("eventUrlMapping.replaceString")
    )

  def sendAllMailsNow(domain: String = default): Action[AnyContent] =
    Action.async { implicit request =>
      routers(domain) match {
        case Some(router) =>
          sendAll(router.outboxes).map { r =>
            Ok(Json.toJson(r))
          }
        case None =>
          val message = s"Unknown domain: $domain"
          logger.warn(s"Bad request: reason: $message")
          Future(BadRequest(Json.obj("statusCode" -> 400, "message" -> message)))
      }
    }

  def emitEventsNow(): Action[AnyContent] =
    Action.async { _ =>
      new EventEmitter(httpClient, eventEmitterConfig, emailEventsRepository).emitEvents
        .map { r =>
          Ok(Json.toJson(r))
        }
    }

  def eventHubItem(transId: String): Action[AnyContent] =
    Action.async { _ =>
      eventHubRepository.eventHubItem(transId).map {
        case Some(event) => Ok(Json.toJson(event.item))
        case _           => Ok(Json.toJson("""{"result": "empty"}"""))
      }
    }

  def markSent(transId: String) =
    Action.async { _ =>
      emailEventsRepository.markSent(transId, senderDomain = "exampleDomain", eventUrl = None).map(_ => Ok(transId))
    }

  def clearMailQueues(domain: String = default): Action[AnyContent] =
    Action.async { _ =>
      routers(domain) match {
        case Some(router) =>
          removeAll(router.outboxes).map { r =>
            Ok(Json.obj("numRemoved" -> r.size))
          }
        case None =>
          val message = s"Unknown domain: $domain"
          logger.warn(s"Bad request: reason: $message")
          Future(BadRequest(Json.obj("statusCode" -> 400, "message" -> message)))
      }
    }

  def sendAllHmrcMailsNow(): Action[AnyContent] = sendAllMailsNow()
  val hmrcEmitEventsNow: Action[AnyContent] = emitEventsNow()

  def sendAll(outboxes: Seq[Outbox])(implicit hc: HeaderCarrier): Future[EmailQueueProcessingResults] =
    Future.reduceLeft(outboxes map { outbox =>
      outbox.sendAll
    }) {
      _.combine(_)
    }

  def removeAll(outboxes: Seq[Outbox]): Future[Seq[DeleteResult]] = {
    val result = outboxes map { outbox =>
      outbox.removeAll()
    }
    Future.sequence(result)
  }

}

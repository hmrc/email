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

package uk.gov.hmrc.email.controllers

import play.api.libs.json.JsValue
import play.api.mvc.{ Action, AnyContent, ControllerComponents }
import uk.gov.hmrc.email.controllers.model.Event
import uk.gov.hmrc.email.services.EventProcessing
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class EventsController @Inject() (
  cc: ControllerComponents,
  eventProcessing: EventProcessing
)(implicit ec: ExecutionContext)
    extends BackendController(cc) {

  def process: Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      withJsonBody[Event] { event =>
        eventProcessing(event).map(status => Created(status.toString))
      }
    }

  def findEvent(transId: String): Action[AnyContent] =
    Action.async { _ =>
      eventProcessing.findEvent(transId).map {
        case Some(item) => Ok(item)
        case _          => NoContent
      }
    }

}

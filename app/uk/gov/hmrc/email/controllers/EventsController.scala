/*
 * Copyright 2023 HM Revenue & Customs
 *
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

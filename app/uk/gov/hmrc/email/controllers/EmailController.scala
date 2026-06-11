/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.controllers

import play.api.libs.json._
import play.api.mvc._
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.email.Auditable
import uk.gov.hmrc.email.controllers.model._
import uk.gov.hmrc.email.controllers.util.ValidationException
import uk.gov.hmrc.email.services.Routers
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.Audit
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.play.bootstrap.config.AppName
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

@Singleton
class EmailController @Inject() (
  routers: Routers,
  auditConnector: AuditConnector,
  cc: ControllerComponents,
  configuration: Configuration
)(implicit ec: ExecutionContext)
    extends BackendController(cc) with Auditable with Logging {

  override def appName: String = AppName.fromConfiguration(configuration)

  override def audit: Audit = Audit(appName, auditConnector)

  lazy val replyToTemplates: Set[String] = configuration
    .getOptional[String]("replyToTemplateIds")
    .getOrElse("")
    .split(",")
    .toSet

  override protected def withJsonBody[T](
    f: T => Future[Result]
  )(implicit request: Request[JsValue], ct: ClassTag[T], reads: Reads[T]): Future[Result] =
    Try(request.body.validate[T]) match {
      case Success(JsSuccess(payload, _)) => f(payload)
      case Failure(e) if e.isInstanceOf[ValidationException] =>
        Future.successful(BadRequest(s"""{"reason": "${e.getMessage}"}"""))
      case Success(JsError(errs)) =>
        Future.successful(BadRequest(jsErrorFormat(errs)))
      case Failure(e) =>
        Future.successful(buildBadRequest(s"could not parse body due to ${e.getMessage}"))
    }

  def jsErrorFormat(errs: scala.collection.Seq[(JsPath, scala.collection.Seq[JsonValidationError])]): String = {
    val errorBody = errs
      .map {
        case (x, errors) if x.path.headOption.exists(_.toString == "/to") =>
          s""""email: ${errors.map(_.message).mkString(", ")}""""
        case (_, errors) =>
          s""""${errors.map(_.message).mkString(", ")}""""
      }
      .mkString(", ")
    s"""{"reason": $errorBody}"""
  }

  private def buildBadRequest(message: String, ser: Option[SendEmailRequest] = None): Result = {
    val templateId = ser match {
      case Some(_) => s"for templateId: ${ser.get.templateId}"
      case _       => ""
    }

    logger.warn(s"Bad request: templateId: $templateId reason: $message")
    BadRequest(Json.obj("statusCode" -> 400, "message" -> message))
  }

  def send(senderDomain: String): Action[JsValue] =
    Action.async(parse.json) { implicit request =>
      withJsonBody[SendEmailRequest] { ser =>
        ser.replyToAddress match {
          case Some(_) if ser.to.exists(!_.value.endsWith("gov.uk")) =>
            Future(buildBadRequest("Cannot use Reply-To-Address when sending to a non gov.uk domain", Some(ser)))
          case Some(_) if !replyToTemplates.contains(ser.templateId) =>
            Future(buildBadRequest("Forbidden use of Reply-To-Address", Some(ser)))
          case _ =>
            routers(senderDomain)
              .fold(Future(buildBadRequest(s"Unknown domain: $senderDomain", Some(ser)))) { router =>
                router.store(ser).map {
                  case Right(_) =>
                    if (ser.to.isEmpty || ser.to.size > 1)
                      sendDataEvent(
                        transactionName = "Send To - Empty or Multiple",
                        path = routes.EmailController.send(senderDomain).url,
                        detail = Map("emails" -> ser.to.mkString(", "))
                      )
                    Accepted
                  case Left(error) => buildBadRequest(error.reason, Some(ser))
                }
              }
        }
      }
    }

  def sendTemplatedEmail(): Action[JsValue] =
    Action.async(parse.json) { request =>
      send("hmrc")(request)
    }
}

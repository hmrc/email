/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import play.api.Logging
import play.api.libs.json.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.RenderResult
import uk.gov.hmrc.email.services.Priority.Priority
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, UpstreamErrorResponse }
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import scala.language.implicitConversions
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try
import scala.util.matching.Regex
import play.api.libs.ws.writeableOf_JsValue

class EmailRendererConnector(renderer: String, serviceConfig: ServicesConfig, httpClient: HttpClientV2)(implicit
  ec: ExecutionContext
) extends Logging {

  type PrioritisedRenderResult = (Option[Priority], RenderResult)

  def baseUrl: String = serviceConfig.baseUrl(renderer)

  def render(templateId: String, parameters: Map[String, String], emails: List[EmailAddress])(implicit
    hc: HeaderCarrier
  ): Future[Either[ErrorMessage, PrioritisedRenderResult]] = {
    def base64Decode(result: String): String =
      new String(Base64.getDecoder.decode(result), StandardCharsets.UTF_8)
    logger.warn("EmailRendererConnectorCall")
    TemplateRenderRequest(parameters, takeOnlyIfOneEmail(emails))

    httpClient
      .post(s"$baseUrl/templates/$templateId")
      .withBody(Json.toJson(TemplateRenderRequest(parameters, takeOnlyIfOneEmail(emails))))
      .execute[TemplateRenderResult]
      .map { result =>
        logger.warn(s"EmailRendererConnectorResult subject: ${result.subject}")
        Right(
          result.priority -> RenderResult(
            base64Decode(result.plain),
            base64Decode(result.html),
            result.fromAddress,
            result.subject,
            result.service,
            result.templateId
          )
        )
      } recover {
      case up: UpstreamErrorResponse if up.statusCode == 404 =>
        Left(ErrorMessage(s"Template $templateId does not exist"))
      case up: UpstreamErrorResponse if up.statusCode == 400 =>
        Left(ErrorMessage.fromExceptionReason(up.getMessage))
      case e =>
        logger.error(s"EmailRendererFailed ${e.getMessage}")
        Left(ErrorMessage(s"TemplateRenderer failed for unknown reason for $templateId"))
    }
  }

  def takeOnlyIfOneEmail(emails: List[EmailAddress]): Option[String] =
    emails match {
      case first :: Nil => Some(first.value)
      case _            => None
    }
}

case class ErrorMessage(reason: String)

object ErrorMessage {
  val p: Regex = ".*Response body '(.*)'".r

  def fromExceptionReason(exceptionMessage: String): ErrorMessage = {
    def extractReason(json: JsValue) =
      for {
        values <- json.asOpt[Map[String, String]]
        reason <- values.get("reason")
      } yield reason

    exceptionMessage match {
      case p(errorMsg) =>
        ErrorMessage(
          Try(Json.parse(errorMsg)).toOption
            .flatMap(extractReason)
            .getOrElse(exceptionMessage)
        )
      case _ => ErrorMessage(exceptionMessage)
    }
  }
}

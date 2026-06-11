/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import play.api.libs.json._
import uk.gov.hmrc.email.services.Priority.Priority
import uk.gov.hmrc.http.{ HttpErrorFunctions, HttpReads, HttpResponse }
import cats.syntax.either._

case class TemplateRenderResult(
  plain: String,
  html: String,
  fromAddress: String,
  subject: String,
  service: String,
  priority: Option[Priority],
  templateId: Option[String]
)

object TemplateRenderResult {
  implicit val priorityWrites: Writes[Priority] = Writes[Priority] { case priority =>
    JsString(priority.toString)
  }
  implicit val templateRenderResultFormat: OFormat[TemplateRenderResult] = Json.format[TemplateRenderResult]

  implicit object TemplateRenderResultHttpReads extends HttpReads[TemplateRenderResult] with HttpErrorFunctions {
    override def read(method: String, url: String, response: HttpResponse): TemplateRenderResult =
      handleResponseEither(method, url)(response)
        .map(resp => templateRenderResultFormat.reads(resp.json).get)
        .valueOr(upstream => throw upstream)
  }
}

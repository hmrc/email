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

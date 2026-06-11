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

import org.apache.commons.codec.binary.Base64
import play.api.Logging
import play.api.http.Status.*
import play.api.libs.json.*
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.mailgun.*
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.services.SenderDomainConfiguration
import uk.gov.hmrc.email.utils.NonEmptyString
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HeaderNames, HttpResponse, UpstreamErrorResponse }
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions
import play.api.libs.ws.writeableOf_urlEncodedForm

class MailgunConnector(
  senderDomainConfiguration: SenderDomainConfiguration,
  mailgunClient: HttpClientV2,
  override val mailgunBaseUrl: String
)(implicit ec: ExecutionContext)
    extends MailgunAPI with Logging {

  val apiKey: String = senderDomainConfiguration.mailgun.apiKey
  val publicApiKey: String =
    senderDomainConfiguration.mailgun.publicApiKey

  private val apiKeyBase64: String = new String(Base64.encodeBase64(s"api:$apiKey".getBytes("UTF-8")))
  private val publicApiKeyBase64: String = new String(Base64.encodeBase64(s"api:$publicApiKey".getBytes("UTF-8")))

  def validate(email: NonEmptyString): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    mailgunClient
      .get(validateUrl(email.unwrap))
      .setHeader(HeaderNames.authorisation -> s"Basic $publicApiKeyBase64")
      .withProxy
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case OK =>
            Json
              .parse(response.body)
              .as[EmailAddressValidationResponse]
              .is_valid
          case REQUEST_ENTITY_TOO_LARGE =>
            logger.warn("Mailgun returned status 413 on email validation")
            true
          case status =>
            logger.warn(s"Mailgun returned status $status on email validation")
            false
        }
      )
  }

  def send(message: EmailMessage): Future[MailgunSendResponse] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    mailgunClient
      .post(sendUrl)
      .setHeader((HeaderNames.authorisation, s"Basic $apiKeyBase64"))
      .withProxy
      .withBody(Converters.emailToFormBody(message))
      .execute[HttpResponse]
      .map(_.json.as[MailgunSendResponse])

  }

  def deleteBouncesFor(emailAddress: EmailAddress): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    mailgunClient
      .delete(deleteBouncesUrl(emailAddress.value))
      .withProxy
      .setHeader(HeaderNames.authorisation -> s"Basic $apiKeyBase64")
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
      .map {
        case Right(_)                                                               => true
        case Left(upstreamErrorResponse) if upstreamErrorResponse.statusCode == 404 => false
        case Left(error)                                                            => throw error
      }
  }

  override lazy val senderDomainName: String = senderDomainConfiguration.name

  override def toString: String =
    s"${this.getClass.getSimpleName}: sender domain: $senderDomainName"
}

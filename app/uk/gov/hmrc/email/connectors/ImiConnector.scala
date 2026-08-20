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

package uk.gov.hmrc.email.connectors

import play.api.Logging
import play.api.http.Status
import play.api.libs.json.{ JsValue, Json }
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.imi.IMIConfiguration
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.email.services.SenderDomainConfiguration
import uk.gov.hmrc.email.utils.ImplicitConversions.{ seqToQueryString, stringToURL }
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.language.implicitConversions

@Singleton
class ImiConnector @Inject() (
  senderDomainConfiguration: SenderDomainConfiguration,
  httpClient: HttpClientV2,
  imiServiceKey: String,
  override val imiBaseUrl: String,
  override val imiConsentBaseUrl: String,
  audit: AuditConnector
)(implicit executionContext: ExecutionContext)
    extends Logging with IMIConfiguration {
  val apiKey: String = senderDomainConfiguration.imi.apiKey
  def send(message: EmailContent): Future[Either[ImiError, ImiSendResponse]] = {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    httpClient
      .post(sendUrl)
      .withBody(Json.toJson(message))
      .withProxy
      .setHeader(("Content-Type", "application/json"), ("key", senderDomainConfiguration.imi.apiKey))
      .execute[HttpResponse]
      .map { response =>
        response.status match {
          case Status.CREATED =>
            Right(response.json.as[ImiSendResponse])
          case Status.UNAUTHORIZED =>
            Left(ImiError("AUTHENTICATION_FAILED", "Imi Authentication failed"))
          case Status.BAD_REQUEST =>
            Left(ImiError("INVALID_PAYLOAD", s"${response.json}"))
          case status =>
            Left(ImiError("SERVER_ERROR", s"Imi failed with status code $status and response ${response.json}"))
        }
      }
      .recover { case e: Exception =>
        Left(ImiError("HTTP_ERROR", s"Http request to imi failed with error: ${e.getMessage} "))
      }
  }

  def getConsent(emailAddress: EmailAddress, groupId: String): Future[Option[ConsentItem]] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val authHeader: (String, String) = ("authorization", s"Bearer $imiServiceKey")

    httpClient
      .get(consentUrl(groupId) + seqToQueryString(Seq(("address", emailAddress.value))))
      .withProxy
      .setHeader(authHeader)
      .execute[List[ConsentItem]]
      .map(_.headOption)
  }

  def getConsentList(groupId: String, continueToken: Option[String], pageSize: Long): Future[ConsentItemList] = {
    logger.warn(s"getConsentList for groupId $groupId")
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val authHeader: (String, String) = ("authorization", s"Bearer $imiServiceKey")
    val queryStrings = Seq(("format", "JSON"), ("pageSize", pageSize.toString))
    val continueTokenQueryString = continueToken.map(token => ("continuationToken", token))

    httpClient
      .get(consentUrl(groupId) + seqToQueryString(queryStrings ++ continueTokenQueryString))
      .withProxy
      .setHeader(authHeader)
      .execute[HttpResponse]
      .map(response => ConsentItemList(response.json.as[List[ConsentItem]], response.header("X-cp-continue-token")))
      .recover { case e: Exception =>
        logger.error(s"IMI getConsentList call Exception")
        val _ = audit.sendEvent(
          DataEvent(
            "email",
            tags = Map("transactionName" -> "getConsentListError"),
            detail = Map("url" -> consentUrl(groupId), "errorMessage" -> e.getMessage),
            auditType = EventTypes.Succeeded
          )
        )
        ConsentItemList(List.empty[ConsentItem], None)
      }
  }

  def addConsent(emailAddress: String, groupId: String, consent: Boolean, reason: String): Future[Boolean] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    import ImiConsent.formatWrites
    val imiConsent: ImiConsent = ImiConsent(Channel.EMAIL, emailAddress, consent, reason)

    httpClient
      .post(consentUrl(groupId))
      .withBody(Json.toJson(imiConsent))
      .withProxy
      .setHeader(("Content-Type", "application/json"), ("authorization", s"Bearer $imiServiceKey"))
      .execute[HttpResponse]
      .map(response =>
        response.status match {
          case Status.NO_CONTENT => true
          case e =>
            logger.error(s"Consent returned an error with status: $e")
            throw new RuntimeException(s"Consent returned an error with status: $e")
        }
      )
  }

  def deleteConsent(
    emailAddress: EmailAddress,
    groupId: String,
    oldList: Boolean = false
  ): Future[DeleteConsentResponse] = {
    val startTime = System.currentTimeMillis()

    logger.warn(s"Deleting consent item for group: $groupId, oldList: $oldList")

    implicit val hc: HeaderCarrier = HeaderCarrier()
    val header = ("authorization", s"Bearer $imiServiceKey")
    val emailEncoded = URLEncoder.encode(emailAddress.value, StandardCharsets.UTF_8.toString)

    httpClient
      .delete(deleteConsentUrl(groupId, emailEncoded))
      .setHeader(header)
      .withProxy
      .execute[HttpResponse]
      .map { response =>
        val duration = System.currentTimeMillis() - startTime
        response.status match {
          case Status.OK =>
            DeleteConsentSuccess
          case Status.NOT_FOUND =>
            logger.warn(s"Consent item for group $groupId NOT_FOUND ${duration}ms)")
            DeleteConsentNotFound
          case status if status >= 400 && status < 500 =>
            logger.error(s"Consent item Failed to delete ${duration}ms)")
            DeleteConsentFailed(Some(status), s"Failed to delete $status")
          case status =>
            logger.error(s"ERROR, IMI deleteConsent status $status $duration ms")
            DeleteConsentFailed(Some(status), s"Failed to delete, unexpected error $status")
        }
      }
      .recover { case e: Exception =>
        val duration = System.currentTimeMillis() - startTime
        logger.error(s"IMI deleteConsent call Exception $duration ms: ${e.getMessage}")
        DeleteConsentFailed(None, s"Imi server error ${e.getMessage}")
      }

  }
}

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

package util

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Seconds, Span }
import play.api.libs.json.*
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.auth.core.{ Enrolment, EnrolmentIdentifier }
import uk.gov.hmrc.domain.*
import uk.gov.hmrc.domain.TaxIds.TaxIdWithName
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.implicitConversions

@Singleton
class AuthHelper @Inject() (httpClient: HttpClientV2) extends ScalaFutures {

  implicit val headerCarrier: HeaderCarrier = new HeaderCarrier()

  private def makeEnrolment(identifier: TaxIdWithName, identifierKey: String): Enrolment =
    Enrolment(
      identifier.name,
      Seq(EnrolmentIdentifier(identifierKey, identifier.value)),
      "Activated"
    )

  private def makeFhddsEnrolment(fhddsIdent: HmrcObtdsOrg) =
    makeEnrolment(fhddsIdent, "EtmpRegistrationNumber")

  private def makeVatEnrolement(vatIdent: HmrcMtdVat) =
    makeEnrolment(vatIdent, "VRN")

  private val STRIDE_PAYLOAD = Json.obj(
    "clientId"   -> "id",
    "enrolments" -> JsArray.empty,
    "ttl"        -> JsNumber(1200)
  )

  private val GG_BASE_PAYLOAD: JsObject = Json.obj(
    "credId"             -> "1235",
    "affinityGroup"      -> "Organisation",
    "confidenceLevel"    -> 200,
    "credentialStrength" -> "none",
    "enrolments"         -> JsArray.empty
  )

  private def taxIdKey(taxId: TaxIdentifier) =
    taxId match {
      case _: SaUtr => "IR-SA"
      case _        => "IR-CT"
    }

  private def enrolmentPayload(taxId: TaxIdentifier) =
    Json.obj(
      "key" -> s"${taxIdKey(taxId)}",
      "identifiers" -> JsArray(
        IndexedSeq(
          Json.obj(
            "key"   -> "UTR",
            "value" -> taxId.value
          )
        )
      ),
      "state" -> "Activated"
    )

  private def addUtrToPayload(payload: JsObject, utr: TaxIdentifier) =
    payload
      .transform((__ \ "enrolments").json.update(__.read[JsArray].map { case JsArray(arr) =>
        JsArray(arr :+ enrolmentPayload(utr))
      }))
      .get

  private def addEnrolmentToPayload(payload: JsObject, enrolment: Enrolment) = {
    implicit val idaFormat = Json.format[EnrolmentIdentifier]
    val enrolmentWrites = Json.writes[Enrolment]
    payload
      .transform(
        (__ \ "enrolments").json.update(
          __.read[JsArray]
            .map { case JsArray(arr) =>
              JsArray(arr :+ Json.toJson(enrolment)(enrolmentWrites))
            }
        )
      )
      .get
  }

  private def addNinoToPayload(payload: JsObject, nino: Nino) =
    payload ++ Json.obj("nino" -> nino.value)

  def authorisedTokenFor(baseUrl: String, ids: TaxIdentifier*): Future[String] =
    buildUserToken(
      baseUrl,
      ids
        .foldLeft(GG_BASE_PAYLOAD)((payload, taxId) =>
          taxId match {
            case saUtr: SaUtr => addUtrToPayload(payload, saUtr)
            case nino: Nino   => addNinoToPayload(payload, nino)
            case ctUtr: CtUtr => addUtrToPayload(payload, ctUtr)
            case fhdds: HmrcObtdsOrg =>
              addEnrolmentToPayload(payload, makeFhddsEnrolment(fhdds))
            case vat: HmrcMtdVat =>
              addEnrolmentToPayload(payload, makeVatEnrolement(vat))
            case _ => throw new RuntimeException("No matching taxId")
          }
        )
        .toString
    )

  def authHeader(baseUrl: String, taxId: TaxIdentifier): (String, String) = {
    val bearerToken = authorisedTokenFor(baseUrl, taxId).futureValue
    ("Authorization", bearerToken)
  }

  def buildUserToken(baseUrl: String, payload: String): Future[String] = {
    val response = httpClient
      .post(s"$baseUrl/government-gateway/session/login")
      .withBody(Json.parse(payload))
      .setHeader(("Content-Type", "application/json"))
      .execute[HttpResponse]
      .futureValue(timeout(Span(10, Seconds)))
    Future.successful(response.header("Authorization").get)
  }

  def buildStrideToken(baseUrl: String): Future[String] = {
    val response =
      httpClient
        .post(s"$baseUrl/auth/sessions")
        .withBody(STRIDE_PAYLOAD)
        .setHeader(("Content-Type", "application/json"))
        .execute[HttpResponse]
        .futureValue(timeout(Span(10, Seconds)))
    Future.successful(response.header("Authorization").get)
  }

}

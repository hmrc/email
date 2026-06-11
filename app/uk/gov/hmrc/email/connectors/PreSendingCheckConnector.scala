/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.connectors

import play.api.Logging
import play.api.libs.json.{ Format, Json }
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import scala.language.implicitConversions
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import java.net.{ MalformedURLException, UnknownHostException }
import javax.inject.Inject
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal

case class PreSendingCheckConnector @Inject() (httpClient: HttpClientV2)(implicit ec: ExecutionContext)
    extends Logging {

  implicit val alertReads: HttpReads[SendAlertResponse] =
    (method: String, url: String, response: HttpResponse) =>
      response.status match {
        case play.api.http.Status.OK => response.json.as[SendAlertResponse]
        case status                  => throw new Exception(s"$method $url failed with status code $status")
      }

  def shouldISend(expectedCallbackUrl: String)(implicit hc: HeaderCarrier): Future[Either[Boolean, SendAlertResponse]] =
    httpClient.get(expectedCallbackUrl).execute[SendAlertResponse].map(Right(_)).recoverWith {
      case e @ (_: UnknownHostException | _: MalformedURLException) =>
        logger.warn(s"GET $expectedCallbackUrl failed due to ${e.getMessage}")
        Future.successful(Left(true))
      case NonFatal(e) =>
        logger.warn(s"GET $expectedCallbackUrl failed due to ${e.getMessage}")
        Future.successful(Left(false))
    }
}

final case class SendAlertResponse(sendAlert: Boolean)
object SendAlertResponse {
  implicit val formats: Format[SendAlertResponse] =
    Json.format[SendAlertResponse]
}

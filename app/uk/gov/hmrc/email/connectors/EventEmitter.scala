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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import play.api.Logging
import play.api.libs.json.{ JsString, JsValue, Json, OFormat, Writes }
import uk.gov.hmrc.email.model.EventType
import uk.gov.hmrc.email.repositories.model.EmailEventsItem
import uk.gov.hmrc.email.repositories.EmailEventsRepository
import uk.gov.hmrc.email.scheduled.EventEmitterConfig
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpReads, HttpResponse }
import uk.gov.hmrc.mongo.workitem.{ ProcessingStatus, WorkItem }
import scala.language.implicitConversions
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import play.api.libs.ws.writeableOf_JsValue
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.matching.Regex

class EventEmitter(
  httpClient: HttpClientV2,
  eventEmitterConfig: EventEmitterConfig,
  emailEventsRepository: EmailEventsRepository
)(implicit ec: ExecutionContext, mat: Materializer)
    extends Logging {

  implicit val legacyRawReads: HttpReads[HttpResponse] =
    HttpReads.Implicits.throwOnFailure(HttpReads.Implicits.readEitherOf(using HttpReads.Implicits.readRaw))

  private val failureResponse = """(4|5[0-9]{2})""".r

  implicit val eventWrites: Writes[Map[EventType, Instant]] {
    type Entry = (EventType, Instant)
  } = new Writes[Map[EventType, Instant]] {

    implicit val instantWrite: Writes[Instant] = new Writes[Instant] {
      def writes(instant: Instant): JsValue =
        JsString(DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.MILLIS)))
    }

    type Entry = (EventType, Instant)

    val externalEventsOnly: Entry => Boolean = { case (eventType, _) =>
      eventType.emitExternally
    }
    val detectedTimeOrdering: (Entry, Entry) => Boolean = { case ((_, t1), (_, t2)) =>
      t1 `isAfter` t2
    }
    val toEvent: Entry => JsValue = { case (event, time) =>
      Json.obj("event" -> event, "detected" -> time)
    }

    def writes(o: Map[EventType, Instant]): JsValue =
      Json.obj(
        "events" -> o.toSeq
          .filter(externalEventsOnly)
          .sortWith(detectedTimeOrdering)
          .map(toEvent)
      )
  }

  def emitEvents: Future[EventEmitterResults] =
    Source
      .unfoldAsync[Unit, WorkItem[EmailEventsItem]](())(_ =>
        emailEventsRepository.pullOutstanding().map(_.map(w => ((), w)))
      )
      .runFoldAsync(EventEmitterResults(emitted = 0, failed = 0))(sendImiEvents)

  def sendImiEvents(
    previousResults: EventEmitterResults,
    work: WorkItem[EmailEventsItem]
  ): Future[EventEmitterResults] = {
    logger.warn("sendImiEventsIMI")
    implicit val hc: HeaderCarrier = HeaderCarrier()
    (work.item.eventUrl match {
      case Some(url) =>
        httpClient.post(replace(url)).withBody(Json.toJson(work.item.events)).execute.flatMap { response =>
          response.status.toString match {
            case failureResponse(code) =>
              logger.warn(s"Callback to $url failed with status code $code")
              emailEventsRepository
                .markComplete(work, ProcessingStatus.Failed)
                .map(_ => previousResults.incrementFailed)
            case _ =>
              logger.info(s"Callback to URL $url returned HTTP response status ${response.status}")
              emailEventsRepository
                .markComplete(work, ProcessingStatus.Succeeded)
                .map(_ => previousResults.incrementEmitted)
          }
        }
      case None => emailEventsRepository.markComplete(work, ProcessingStatus.Succeeded).map(_ => previousResults)
    }).recoverWith { case e: Exception =>
      logger.warn(s"Callback URL returned $e")
      emailEventsRepository.markComplete(work, ProcessingStatus.Failed).map(_ => previousResults.incrementFailed)
    }
  }

  def r: Regex = eventEmitterConfig.regex.r

  def newEventUrlDomain: String = eventEmitterConfig.replaceString

  def replace(url: String): String =
    if (url.contains("http://localhost:8080"))
      url
    else {
      val replacementUrl =
        r.replaceAllIn(url, m => m.group(1) + newEventUrlDomain + m.group(3))
      val secureReplacementUrl = replacementUrl
        .replaceFirst("http:", "https:")
        .replaceFirst(":80/", ":443/")
      if (secureReplacementUrl != url)
        logger.warn(
          s"changing obsolete eventUrl from $url to $secureReplacementUrl"
        )
      secureReplacementUrl
    }

}

case class EventEmitterResults(emitted: Int, failed: Int) {
  def incrementEmitted: EventEmitterResults = copy(emitted = emitted + 1)
  def incrementFailed: EventEmitterResults = copy(failed = failed + 1)
}

object EventEmitterResults {
  implicit val formats: OFormat[EventEmitterResults] =
    Json.format[EventEmitterResults]
}

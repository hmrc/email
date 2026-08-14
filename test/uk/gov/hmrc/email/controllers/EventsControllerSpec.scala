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

package uk.gov.hmrc.email.controllers

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{ verify, when }
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.ContentTypes
import play.api.http.Status.{ CREATED, NO_CONTENT, OK }
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.Helpers.{ CONTENT_TYPE, GET, POST, contentAsString, defaultAwaitTimeout, status, stubControllerComponents }
import play.api.test.{ FakeHeaders, FakeRequest }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.controllers.model.Event
import uk.gov.hmrc.email.model.EventMarkingStatus
import uk.gov.hmrc.email.services.EventProcessing
import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EventsControllerSpec extends SpecBase with ScalaFutures {
  "process function" must {
    "call the EventProcessing service with correct event and return CREATED" in new TestCase {
      when(mockEventProcessing.apply(any[Event], any[String], any[UUID]))
        .thenReturn(Future.successful(EventMarkingStatus.Marked))
      val result = controller.process.apply(request)

      verify(mockEventProcessing).apply(any[Event], any[String], any[UUID])
      status(result) mustEqual CREATED
      contentAsString(result) mustBe EventMarkingStatus.Marked.toString
    }

    "find an event and return Ok" in new TestCase {
      val transId = "123"
      val fakeRequest = FakeRequest(GET, s"/event/$transId")
      when(mockEventProcessing.findEvent(any[String])).thenReturn(Future.successful(Some("event")))

      val result: Future[Result] = controller.findEvent(transId).apply(fakeRequest)

      status(result) mustBe OK
      contentAsString(result) mustBe "event"
    }
  }

  "find an event and return NoContent if not found" in new TestCase {
    val transId = "456"
    val fakeRequest = FakeRequest(GET, s"/event/$transId")

    when(mockEventProcessing.findEvent(any[String])).thenReturn(Future.successful(None))

    val result: Future[Result] = controller.findEvent(transId).apply(fakeRequest)

    status(result) mustBe NO_CONTENT
  }
}

class TestCase {
  val mockEventProcessing: EventProcessing = mock[EventProcessing]
  implicit lazy val materializer: Materializer = mock[Materializer]
  val randomUuid = UUID.randomUUID()
  val localDateTime = LocalDateTime.now()

  val payloadString =
    s"""{
       |  "messageId": "4310b3f8-9d89-47a3-9c72-4482f9ef14c9",
       |  "correlationId": "4310b3f8-9d89-47a3-9c72-4482f9ef14c9",
       |  "status": "Delivered",
       |  "timeStamp": "$localDateTime",
       |  "code": "7520",
       |  "description": "Delivered",
       |  "additionalInfo": "",
       |  "emailAddress": "test.dc@digital.hmrc.gov.uk",
       |  "tags": {
       |    "templateId": "vat",
       |    "regime": "sa",
       |    "platform": "mdtp"
       |  }
       |}""".stripMargin

  val json = Json.parse(payloadString)
  val fakeHeaders = FakeHeaders(Seq(CONTENT_TYPE -> ContentTypes.JSON))

  val request = FakeRequest(POST, "/events", fakeHeaders, json)
  val controller = new EventsController(stubControllerComponents(), mockEventProcessing)

}

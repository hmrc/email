/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.email

import uk.gov.hmrc.email.emailaddress.EmailAddress

import java.time.{ Instant, LocalDateTime }
import java.util.UUID

object TestData {
  val TEST_EMAIL = "test@test.com"
  val TEST_TEMPLATE_ID = "test_template_id"
  val TEST_TEMPLATE_REGIME = "test_generic"
  val TEST_SUBJECT = "test_sub"
  val TEST_FROM_ADDRESS = "test_address"
  val TEST_HTML = "<head>test</head>"
  val TEST_PLAIN_TEXT = "test_title_text"
  val TEST_SERVICE = "test_service"
  val TEST_GROUP_ID = "test_group_id"
  val TEST_ID = "test_id"
  val TEST_MESSAGE_ID = "1fghj234578999#uytre"
  val TEST_STATS_NAME = "test_name"
  val TEST_EVENT_TYPE = "test_event"
  val TEST_TOKEN = "12357890"
  val TEST_CHANNEL = "test_channel"
  val TEST_ENROLMENT = "HMRC-CUS-ORG"
  val TEST_DESCRIPTION = "test_description"
  val TEST_URL = "www.test.com"
  val TEST_PARAMETERS_MAP: Map[String, String] = Map("test_key" -> "test_value")

  val TEST_EMAIL_ADDRESS_VALUE = "test@test.com"
  val TEST_EMAIL_ADDRESS: EmailAddress = EmailAddress(TEST_EMAIL_ADDRESS_VALUE)
  val TEST_DOMAIN = "hmrc"
  val TEST_EVENT = "test_event"
  val TEST_REASON = "test_reason"
  val TEST_MESSAGE = "test_message"
  val TEST_INFO = "test_info"
  val TEST_DESTINATION_TYPE = "email"

  val EMPTY_STRING = ""

  val TEST_EMAIL_STATS_COUNT = 10
  val TEST_YEAR = 2025
  val TEST_MONTH = 12
  val TEST_DAY = 6
  val TEST_HOUR = 11
  val TEST_MINUTES = 30
  val TEST_SECONDS = 50

  val TEST_LOCAL_DATETIME: LocalDateTime =
    LocalDateTime.of(TEST_YEAR, TEST_MONTH, TEST_DAY, TEST_HOUR, TEST_MINUTES, TEST_SECONDS)

  val TEST_EPOCH_MILLISECONDS = 65478234L
  val TEST_TIME_INSTANT: Instant = Instant.ofEpochMilli(TEST_EPOCH_MILLISECONDS)

  val TEST_RANDOM_UUID: UUID = UUID(0, 0)
}

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

package uk.gov.hmrc.email.utils

import org.scalatestplus.play.PlaySpec

class NonEmptyStringSpec extends PlaySpec {
  "NonEmptyString QueryStringBindable" should {
    "bind a non-empty string" in {
      val queryKey = "value"
      val queryValue = "Hello"

      val queryParameters = Map(queryKey -> Seq(queryValue))

      val result: Option[Either[String, NonEmptyString]] =
        NonEmptyString.nonEmptyString.bind(queryKey, queryParameters)

      result mustBe Some(Right(NonEmptyString("Hello")))
    }

    "fail to bind an empty string" in {
      val queryKey = "value"
      val queryValue = "" // Empty string

      val queryParameters = Map(queryKey -> Seq(queryValue))

      val result: Option[Either[String, NonEmptyString]] =
        NonEmptyString.nonEmptyString.bind(queryKey, queryParameters)

      result mustBe Some(Left("Empty String"))
    }

    "unbind a NonEmptyString to a string" in {
      val nonEmptyStr = NonEmptyString("Hello")

      val result: String = NonEmptyString.nonEmptyString.unbind("value", nonEmptyStr)

      result mustBe "Hello"
    }
  }

}

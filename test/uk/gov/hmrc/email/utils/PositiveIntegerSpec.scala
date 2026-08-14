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
import scala.language.implicitConversions

class PositiveIntegerSpec extends PlaySpec {
  "PositiveInteger QueryStringBindable" should {
    "bind a valid positive integer" in {
      val queryKey = "value"
      val queryValue = "42"

      val queryParameters = Map(queryKey -> Seq(queryValue))

      val result =
        PositiveInteger.positiveInteger.bind(queryKey, queryParameters)

      result.get mustBe Right(PositiveInteger(42))
    }
    "fail to bind a non-positive integer" in {
      val queryKey = "value"
      val queryValue = "-1" // Non-positive integer

      val queryParameters = Map(queryKey -> Seq(queryValue))

      val result =
        PositiveInteger.positiveInteger.bind(queryKey, queryParameters)

      result.get mustBe Left("-1 <= 0")
    }

    "unbind a PositiveInteger to a string" in {
      val positiveInt: PositiveInteger = PositiveInteger(42)

      val result: String = PositiveInteger.positiveInteger.unbind("value", positiveInt)

      result mustBe "42"
    }

  }

}

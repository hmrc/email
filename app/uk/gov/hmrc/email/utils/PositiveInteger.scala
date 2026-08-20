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

package uk.gov.hmrc.email.utils

import play.api.mvc.QueryStringBindable

final case class PositiveInteger private[utils] (unwrap: Int)
object PositiveInteger {
  implicit def positiveInteger(implicit binder: QueryStringBindable[Int]): QueryStringBindable[PositiveInteger] =
    new QueryStringBindable[PositiveInteger] {
      def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, PositiveInteger]] = {
        val bound = binder.bind(key, params).map {
          _.flatMap(PositiveInteger.validate)
        }
        bound
      }

      def unbind(key: String, natural: PositiveInteger): String =
        natural.unwrap.toString
    }

  def validate(value: Int): Either[String, PositiveInteger] =
    if (value <= 0)
      Left(s"$value <= 0")
    else
      Right(PositiveInteger(value))

}

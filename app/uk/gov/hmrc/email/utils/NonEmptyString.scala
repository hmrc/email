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

final case class NonEmptyString private[utils] (unwrap: String)
object NonEmptyString {
  implicit def nonEmptyString(implicit binder: QueryStringBindable[String]): QueryStringBindable[NonEmptyString] =
    new QueryStringBindable[NonEmptyString] {
      def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, NonEmptyString]] =
        binder.bind(key, params).map {
          _.flatMap(NonEmptyString.validate)
        }

      def unbind(key: String, natural: NonEmptyString): String =
        natural.unwrap
    }

  def validate(value: String): Either[String, NonEmptyString] =
    Option(value)
      .filter(_.nonEmpty)
      .fold[Either[String, NonEmptyString]](Left("Empty String")) { value =>
        Right(NonEmptyString(value))
      }
}

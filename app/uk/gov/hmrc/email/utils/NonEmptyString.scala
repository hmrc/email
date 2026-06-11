/*
 * Copyright 2023 HM Revenue & Customs
 *
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

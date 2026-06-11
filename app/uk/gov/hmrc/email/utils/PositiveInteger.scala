/*
 * Copyright 2023 HM Revenue & Customs
 *
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

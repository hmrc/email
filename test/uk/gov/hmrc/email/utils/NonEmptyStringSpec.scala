/*
 * Copyright 2023 HM Revenue & Customs
 *
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

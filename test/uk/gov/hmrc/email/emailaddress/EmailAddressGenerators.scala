/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.emailaddress

import org.scalacheck.Gen
import org.scalacheck.Gen._

trait EmailAddressGenerators {

  def nonEmptyString(char: Gen[Char]): Gen[String] =
    nonEmptyListOf(char)
      .map(_.mkString)
      .suchThat(_.nonEmpty)

  def chars(chars: String): Gen[Char] = Gen.choose(0, chars.length - 1).map(chars.charAt)

  val validMailbox: Gen[String] = nonEmptyString(oneOf(alphaChar, chars(".!#$%&’'*+/=?^_`{|}~-"))).label("mailbox")

  val validDomain: Gen[String] = (for {
    topLevelDomain <- nonEmptyString(alphaChar)
    otherParts     <- listOf(nonEmptyString(alphaChar))
  } yield (otherParts :+ topLevelDomain).mkString(".")).label("domain")

  def validEmailAddresses(mailbox: Gen[String] = validMailbox, domain: Gen[String] = validDomain): Gen[String] =
    for {
      mailbox <- mailbox
      domain  <- domain
    } yield s"$mailbox@$domain"
}

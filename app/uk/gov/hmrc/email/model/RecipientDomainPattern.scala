/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

import com.typesafe.config.ConfigException
import uk.gov.hmrc.email.emailaddress.EmailAddress.Domain

import scala.util.matching.Regex

case class RecipientDomainPattern(domainRegex: Regex) {
  def matches(domain: Domain): Boolean =
    s"$domainRegex".r.findFirstIn(domain.value).isDefined
}

object RecipientDomainPattern {
  def from(s: String): RecipientDomainPattern =
    try RecipientDomainPattern(s.r)
    catch {
      case _: Throwable =>
        throw new ConfigException.BadValue("holdList.domains", s"'$s'must be a valid regex")
    }
}

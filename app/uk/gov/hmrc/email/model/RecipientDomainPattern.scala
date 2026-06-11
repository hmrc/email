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

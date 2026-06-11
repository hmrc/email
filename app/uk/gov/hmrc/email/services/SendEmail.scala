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

package uk.gov.hmrc.email.services

import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model._
import uk.gov.hmrc.email.repositories.model.QueuedEmailRequest
import uk.gov.hmrc.email.utils.Encryption
import java.util.Base64
import javax.inject.{ Inject, Singleton }

@Singleton
class SendEmail @Inject() (
  encryption: Encryption,
  imiConfiguration: ImiConfiguration,
  holdList: List[RecipientDomainPattern],
  allowList: List[RecipientDomainPattern]
) extends Logging {
  protected def tags(request: QueuedEmailRequest, renderResult: RenderResult) = {
    val imiTag = Map("ContactPolicyGroupId" -> imiConfiguration.imiGroupId)
    val mandatoryTags =
      buildMandatoryTags(renderResult.templateRegime, request.templateId, request.auditData.get(Keys.MESSAGE_ID))
    serializeTags(mandatoryTags.view.mapValues(encryption.encrypt(_).value).toMap ++ request.tags ++ imiTag)
  }

  protected def serializeTags(tags: Map[String, String]): String =
    Base64.getEncoder.encodeToString(Json.toJson(tags).toString.getBytes)

  private def buildMandatoryTags(
    templateRegime: String,
    templateId: String,
    messageId: Option[String]
  ): Map[String, String] = {
    val tags = Map("regime" -> s"$templateRegime", "templateId" -> s"$templateId", "platform" -> Platform.MDTP)
    messageId.fold(tags)(id => tags + (Keys.MESSAGE_KEY -> id))
  }

  def checkHoldList(addresses: List[EmailAddress]): Set[(EmailAddress.Domain, RecipientDomainPattern)] =
    addresses.foldRight(Set.empty[(EmailAddress.Domain, RecipientDomainPattern)]) { (address, acc) =>
      holdList.find(_.matches(address.domain)).fold(acc) { heldDomain =>
        acc + ((address.domain, heldDomain))
      }
    }

  def checkAllowList(addresses: List[EmailAddress]): List[EmailAddress] = {
    val result = addresses.filter(address => allowList.isEmpty || allowList.exists(_.matches(address.domain)))
    logger.debug(s"AllowListing: removed email addresses ${addresses.filterNot(result.contains).mkString(", ")}")
    result
  }

}

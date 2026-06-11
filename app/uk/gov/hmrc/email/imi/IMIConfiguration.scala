/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.imi

trait IMIConfiguration {
  val imiBaseUrl: String
  val imiConsentBaseUrl: String
  val sendUrl = s"$imiBaseUrl/v2/messages"
  def consentUrl(groupId: String): String =
    s"$imiConsentBaseUrl/v1/groups/$groupId/members"

  def deleteConsentUrl(groupId: String, emailAddress: String): String =
    s"$imiConsentBaseUrl/v1/groups/$groupId/members?address=$emailAddress"
}

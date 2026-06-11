/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.mailgun

import java.net.URLEncoder

trait MailgunAPI {

  val mailgunBaseUrl: String

  lazy val senderDomainName: String

  val version = "v3"

  private def urlEncode(value: String) = URLEncoder.encode(value, "UTF-8")

  val sendUrl = s"$mailgunBaseUrl/$version/$senderDomainName/messages"

  def validateUrl(email: String): String =
    s"$mailgunBaseUrl/$version/address/validate?address=${urlEncode(email)}"

  def deleteBouncesUrl(emailAddress: String): String =
    s"$mailgunBaseUrl/$version/$senderDomainName/bounces/${urlEncode(emailAddress)}"

}

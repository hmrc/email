/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.services

import org.scalatest.LoneElement
import org.scalatest.concurrent.{ Eventually, IntegrationPatience, ScalaFutures }
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.{ ImiConfiguration, RecipientDomainPattern }
import uk.gov.hmrc.email.utils.Encryption

class SendEmailSpec extends SpecBase with ScalaFutures with IntegrationPatience with LoneElement with Eventually {

  val mockEncryption: Encryption = mock[Encryption]
  val imiConfiguration: ImiConfiguration = new ImiConfiguration(true, "", "")
  val mockHoldList: List[RecipientDomainPattern] = mock[List[RecipientDomainPattern]]
  val mockAllowList: List[RecipientDomainPattern] = mock[List[RecipientDomainPattern]]

  "Holdlist " should {
    "hold recipient domains in the configured holdlist" in {
      val p1 = RecipientDomainPattern.from("gaggle.com")
      val p2 = RecipientDomainPattern.from("b.com")

      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List(p1, p2), mockAllowList)

      val emails: List[EmailAddress] =
        List(EmailAddress("a@a.com"), EmailAddress("a@b.com"), EmailAddress("a@c.com"), EmailAddress("a@gaggle.com"))

      sendEmail.checkHoldList(emails) must be(
        Set(
          (EmailAddress.Domain("b.com"), p2),
          (
            EmailAddress
              .Domain("gaggle.com"),
            p1
          )
        )
      )

      sendEmail.checkHoldList(emails) must be(
        Set(
          (EmailAddress.Domain("b.com"), p2),
          (
            EmailAddress
              .Domain("gaggle.com"),
            p1
          )
        )
      )
    }

    "hold recipient sub domains in the configured holdlist" in {
      val pattern = RecipientDomainPattern.from("btinternet.co.uk|btinternet.com|btopenworld.com")

      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List(pattern), mockAllowList)

      sendEmail.checkHoldList(List(EmailAddress("a@btinternet.com"))) mustBe Set(
        (EmailAddress.Domain("btinternet.com"), pattern)
      )

      sendEmail.checkHoldList(List(EmailAddress("a@btinternet.co.uk"))) mustBe Set(
        (EmailAddress.Domain("btinternet.co.uk"), pattern)
      )

      sendEmail.checkHoldList(List(EmailAddress("a@btopenworld.com"))) mustBe Set(
        (EmailAddress.Domain("btopenworld.com"), pattern)
      )

      sendEmail.checkHoldList(List(EmailAddress("a@subdomain.btinternet.co.uk"))) mustBe Set(
        (EmailAddress.Domain("subdomain.btinternet.co.uk"), pattern)
      )

      sendEmail.checkHoldList(List(EmailAddress("a@btinternet.djc.co.uk"))) mustBe empty
    }

    "does not hold emails not in the domain" in {

      val emails: List[EmailAddress] = List(EmailAddress("a@b.com"))
      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List.empty, mockAllowList)
      sendEmail.checkHoldList(emails) mustBe empty
    }
  }

  "AllowList " should {
    "allow recipient domains in the configured allowList" in {
      val pattern = RecipientDomainPattern.from("digital.hmrc.gov.uk")
      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List.empty, List(pattern))

      val emails = List(
        EmailAddress("a@a.com"),
        EmailAddress("a@b.com"),
        EmailAddress("a@c.com"),
        EmailAddress("a@digital.hmrc.gov.uk"),
        EmailAddress("a@gaggle.com")
      )

      sendEmail.checkAllowList(emails) must contain only EmailAddress("a@digital.hmrc.gov.uk")
    }

    "does not send emails not in the domain" in {
      val pattern = RecipientDomainPattern.from("digital.hmrc.gov.uk")
      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List.empty, List(pattern))
      val emails: List[EmailAddress] = List(EmailAddress("a@b.com"))

      sendEmail.checkAllowList(emails) mustBe empty
    }

    "does send emails if allowList is empty" in {
      val emails: List[EmailAddress] = List(EmailAddress("a@b.com"))
      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List.empty, List.empty)
      sendEmail.checkAllowList(emails) mustBe emails
    }
  }

  it should {
    "send emails regardless if no allowList" in {
      val sendEmail = new SendEmail(mockEncryption, imiConfiguration, List.empty, List.empty)
      val emails: List[EmailAddress] =
        List(
          EmailAddress("a@a.com"),
          EmailAddress("a@b.com"),
          EmailAddress("a@c.com"),
          EmailAddress("a@digital.hmrc.gov.uk"),
          EmailAddress("a@gaggle.com")
        )

      sendEmail.checkAllowList(emails) mustBe emails
    }
  }

}

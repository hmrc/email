/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.utils

import org.scalatestplus.play.PlaySpec
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{ Configuration, Mode }

class EncryptionSpec extends PlaySpec {

  "Encryption" should {
    "encrypt and decrypt a text" in {
      val config = Configuration("crypto.secret" -> "mysecret", "crypto.algorithm" -> "AES")
      val app = new GuiceApplicationBuilder().in(Mode.Test).configure(config).build()

      val encryption = app.injector.instanceOf[Encryption]

      val originalText = "Hello, World!"

      val encryptedText = encryption.encrypt(originalText)
      val decryptedText = encryption.decrypt(encryptedText.value).value

      decryptedText mustBe originalText
    }
  }

}

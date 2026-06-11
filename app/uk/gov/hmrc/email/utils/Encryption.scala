/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.utils

import com.typesafe.config.Config
import uk.gov.hmrc.crypto.{ Crypted, Decrypter, Encrypter, PlainText, SymmetricCryptoFactory }

import javax.inject.Inject

class Encryption @Inject() (config: Config) {
  val configKey = "crypto"
  def crypto: Encrypter & Decrypter = SymmetricCryptoFactory.aesCryptoFromConfig(configKey, config)

  def encrypt(text: String) =
    crypto.encrypt(PlainText(text))

  def decrypt(text: String) =
    crypto.decrypt(Crypted(text))
}

object Encryption {
  val KEY = "enrolment"
}

/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.utils

sealed trait EmailStatus {
  def name: String
}
object EmailStatus {
  case object Sent extends EmailStatus {
    val name = "sent"
  }
  case object Accepted extends EmailStatus {
    val name = "accepted"
  }
  case object Bounced extends EmailStatus {
    val name = "bounced"
  }
  case object Delivered extends EmailStatus {
    val name = "delivered"
  }
  case object Opened extends EmailStatus {
    val name = "opened"
  }
  case object TemporaryBounce extends EmailStatus {
    val name = "temporary_bounce"
  }
  case object Complained extends EmailStatus {
    val name = "complained"
  }
}

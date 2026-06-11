/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.mailgun

import uk.gov.hmrc.email.model.MailgunEvent

sealed trait EventsPage
object EventsPage {
  def apply(events: Seq[Option[MailgunEvent]], nextPage: String): EventsPage =
    if (events.isEmpty)
      EmptyPage
    else
      PageWithEvents(events.collect { case Some(opt) => opt }, nextPage)
}

case object EmptyPage extends EventsPage
case class PageWithEvents(events: Seq[MailgunEvent], nextPage: String) extends EventsPage

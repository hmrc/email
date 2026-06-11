/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.email.services._
trait FakeSenderDomainConfiguration {

  private val mockDefaultQueue: DefaultQueueConfiguration =
    mock[DefaultQueueConfiguration]
  private val mockUrgentQueue: UrgentQueueConfiguration =
    mock[UrgentQueueConfiguration]
  private val mockBackgroundQueue: BackgroundQueueConfiguration =
    mock[BackgroundQueueConfiguration]
  private val mockBounces: BouncesConfiguration = mock[BouncesConfiguration]
  private val mockEvents: EventsConfiguration = mock[EventsConfiguration]
  lazy val senderDomainConfiguration: SenderDomainConfiguration =
    SenderDomainConfiguration(
      name = "sample.domain.gov.uk",
      "",
      false,
      MailgunApiKeys("k1", "k2"),
      ImiApiConfig("", ""),
      None,
      mockDefaultQueue,
      mockUrgentQueue,
      mockBackgroundQueue,
      mockBounces,
      mockEvents
    )

}

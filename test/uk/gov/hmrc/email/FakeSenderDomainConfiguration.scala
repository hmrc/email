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

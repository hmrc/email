/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.AuditExtensions._
import uk.gov.hmrc.play.audit.model.{ Audit, DataEvent, EventTypes }

import scala.concurrent.ExecutionContext

trait Auditable {

  def appName: String
  def audit: Audit

  def sendDataEvent(
    transactionName: String,
    path: String = "N/A",
    tags: Map[String, String] = Map.empty,
    detail: Map[String, String]
  )(implicit hc: HeaderCarrier, ec: ExecutionContext): Unit =
    audit.sendDataEvent(
      DataEvent(
        appName,
        EventTypes.Succeeded,
        tags = hc.toAuditTags(transactionName, path) ++ tags,
        detail = hc.toAuditDetails(detail.toSeq*)
      )
    )

}

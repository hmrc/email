/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.model

sealed trait SaveEventHubResult

case object ItemSaved extends SaveEventHubResult
case object DuplicateEventHubItem extends SaveEventHubResult
case object ItemSaveFailed extends SaveEventHubResult

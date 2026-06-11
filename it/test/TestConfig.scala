/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package test

/*
 * Copyright 2020 HM Revenue & Customs
 *
 */

object TestConfig {

  val stopSchedulers: Map[String, String] = Map(
    "scheduling.urgentQueue.initialDelay"     -> "1 day", // Deliberate - effectively disabled
    "scheduling.backgroundQueue.initialDelay" -> "1 day", // Deliberate - effectively disabled
    "scheduling.defaultQueue.initialDelay"    -> "1 day", // Deliberate - effectively disabled
    "scheduling.loadBounces.initialDelay"     -> "1 day", // Deliberate - effectively disabled
    "scheduling.event-emitter.initialDelay"   -> "1 day" // Deliberate - effectively disabled
  )

  val replyToTemplates: Map[String, String] = Map(
    "replyToTemplateIds" -> "reply-to-template,annual_tax_summaries_message_alert"
  )

  val `specific.denylistedb.co.uk` = "specific.denylistedb.co.uk"
  val `denylisteda.com` = "denylisteda.com"

  val holdlistDomains: Map[String, String] = Map(
    "senderDomains.hmrc.holdList.0" -> `specific.denylistedb.co.uk`,
    "senderDomains.hmrc.holdList.1" -> `denylisteda.com`
  )

  val isImiConnector = Map("imi.threshold" -> 100)

  val hmrc: Map[String, Any] = Map(
    "senderDomains.hmrc.name"                 -> "exampleDomain",
    "senderDomains.hmrc.collection.bounce"    -> "bounce",
    "senderDomains.hmrc.imiConnector"         -> true,
    "senderDomains.hmrc.renderer"             -> "hmrc-email-renderer",
    "senderDomains.hmrc.defaultQueue.rate"    -> "1030000/day",
    "senderDomains.hmrc.backgroundQueue.rate" -> "250000/day",
    "senderDomains.hmrc.imi.groupId"          -> "123456789"
  )

  val transactionengine: Map[String, Any] = Map(
    "senderDomains.transactionengine.name"                 -> "exampleDomain",
    "senderDomains.transactionengine.collection.bounce"    -> "bounce",
    "senderDomains.transactionengine.imiConnector"         -> true,
    "senderDomains.transactionengine.renderer"             -> "hmrc-email-renderer",
    "senderDomains.transactionengine.defaultQueue.rate"    -> "1030000/day",
    "senderDomains.transactionengine.backgroundQueue.rate" -> "250000/day",
    "senderDomains.transactionengine.imi.groupId"          -> "123456789"
  )

  val apicatalogue: Map[String, Any] = Map(
    "senderDomains.apicatalogue.name"                 -> "exampleDomain",
    "senderDomains.apicatalogue.collection.bounce"    -> "bounce",
    "senderDomains.apicatalogue.imiConnector"         -> true,
    "senderDomains.apicatalogue.renderer"             -> "hmrc-email-renderer",
    "senderDomains.apicatalogue.defaultQueue.rate"    -> "1030000/day",
    "senderDomains.apicatalogue.backgroundQueue.rate" -> "250000/day",
    "senderDomains.apicatalogue.imi.groupId"          -> "123456789"
  )

  val voa: Map[String, Any] = Map(
    "senderDomains.voa.name"                 -> "test",
    "senderDomains.voa.renderer"             -> "hmrc-email-renderer",
    "senderDomains.voa.imiConnector"         -> true,
    "senderDomains.voa.mailgun.apiKey"       -> "exampleApiKey",
    "senderDomains.voa.mailgun.publicApiKey" -> "examplePublicApiKey",
    "senderDomains.voa.imi.apiKey"           -> "exampleApiKey",
    "senderDomains.voa.imi.groupId"          -> "groupId",
    "senderDomains.voa.defaultQueue.rate"    -> "86400/day",
    "senderDomains.voa.backgroundQueue.rate" -> "350000/day",
    "senderDomains.voa.imi.groupId"          -> "123456"
  )

  val includeAdminApi: Map[String, String] = Map(
    "play.http.router" -> "testOnlyDoNotUseInAppConf.Routes"
  )

  val metricsGaugesconfig: Map[String, String] = Map(
    "microservice.metrics.gauges.interval"     -> "10 milliseconds",
    "microservice.metrics.gauges.initialDelay" -> "1 second"
  )

  val services: Map[String, Any] = Map(
    "microservice.services.mailgun.host"     -> "localhost",
    "microservice.services.mailgun.port"     -> 8185,
    "microservice.services.imi.host"         -> "localhost",
    "microservice.services.imi.port"         -> 8185,
    "microservice.services.imi-consent.host" -> "localhost",
    "microservice.services.imi-consent.port" -> 8185
  )
}

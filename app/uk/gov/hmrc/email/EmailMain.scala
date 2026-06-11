/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import javax.inject.Inject
import play.api.inject.ApplicationLifecycle
import play.api.{ Application, Logging }
import uk.gov.hmrc.email.metrics.ScheduledMetrics
import uk.gov.hmrc.email.scheduled.*
import uk.gov.hmrc.email.services.Routers
import uk.gov.hmrc.email.streams.EventHubStream
import uk.gov.hmrc.mongo.metrix.MetricOrchestrator

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

class EmailMain @Inject() (
  app: Application,
  lifecycle: ApplicationLifecycle,
  metricOrchestrator: MetricOrchestrator,
  scheduledMetrics: ScheduledMetrics,
  routers: Routers,
  sink: Sink[Unit, ?],
  eventEmitterJobFactory: EventEmitterJobFactory,
  eventHubStream: EventHubStream
)(implicit val ec: ExecutionContext, actorSystem: ActorSystem)
    extends Logging {

  lifecycle.addStopHook(() =>
    Future {
      eventHubStream.shutdown()
      actorSystem.terminate()
      metricOrchestrator
    }
  )

  startScheduledWorkloads(routers, lifecycle)

  private def startScheduledWorkloads(routers: Routers, lifecycle: ApplicationLifecycle): Unit = {
    routers.all.values.foreach { router =>
      new SendEmailJob("defaultQueue", router.defaultOutbox, app.configuration, lifecycle, sink)
      new SendEmailJob("urgentQueue", router.urgentOutbox, app.configuration, lifecycle, sink)
      new SendEmailJob("backgroundQueue", router.backgroundOutbox, app.configuration, lifecycle, sink)
    }

    val senderDomains = routers.all.keySet
    val _ = senderDomains.map(eventEmitterJobFactory(_))
    app.injector.instanceOf[InstanceCounterJob]
    app.injector.instanceOf[SftpEventProcessingJob]
    app.injector.instanceOf[CleanSuppressionListJob]
  }

  private val eventHubEnabled = app.configuration.getOptional[Boolean]("event-hub.enabled").getOrElse(false)

  locally { val _: NotUsed = eventHubStream.start(eventHubEnabled) }

  private val initialDelay =
    getDuration(app, "microservice.metrics.gauges.initialDelay")
  private val refreshInterval =
    getDuration(app, "microservice.metrics.gauges.interval")

  private def getDuration(app: Application, key: String): FiniteDuration =
    app.configuration
      .getOptional[FiniteDuration](key)
      .getOrElse(throw new RuntimeException(s"$key is not specified"))

  logger.warn(s"Metrics job: initialDelay: $initialDelay, refreshInterval: $refreshInterval")

  actorSystem.scheduler.scheduleWithFixedDelay(initialDelay, refreshInterval) { () =>
    logger.warn("Metrics job: executing")
    TimedLog(logger, "Running metrics job", Warning) {
      metricOrchestrator
        .attemptMetricRefresh(Option(scheduledMetrics.resetOn))
        .map(_.log())
        .recover { case e: RuntimeException =>
          logger.error(s"An error occurred processing metrics: ${e.getMessage}", e)
        }
    }
    ()
  }
}

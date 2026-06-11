/*
 * Copyright 2023 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email

import com.google.inject.{ AbstractModule, Provides }
import net.codingwell.scalaguice.ScalaModule
import org.apache.pekko.stream.scaladsl.Sink
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.clusterworkthrottling.{ DefaultServiceInstances, ServiceInstances }
import uk.gov.hmrc.email.config.{ EventHubStreamConfig, SenderDomainConfigurationLoader }
import uk.gov.hmrc.email.metrics.ScheduledMetrics
import uk.gov.hmrc.email.repositories.{ EmailEventsRepository, EventHubRepository }
import uk.gov.hmrc.email.services.TimePeriodLockService
import uk.gov.hmrc.email.utils.DateTimeUtils
import uk.gov.hmrc.mongo.lock.{ LockRepository, LockService, MongoLockRepository }
import uk.gov.hmrc.mongo.metrix.{ MetricOrchestrator, MetricSource, MongoMetricRepository }
import uk.gov.hmrc.mongo.{ MongoComponent, TimestampSupport }
import uk.gov.hmrc.play.bootstrap.metrics.Metrics

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, FiniteDuration, MILLISECONDS }

class EmailModule extends AbstractModule with ScalaModule with Logging {

  override def configure(): Unit =
    bind[EmailMain].asEagerSingleton()

  @Provides
  def sink(): Sink[Unit, ?] = Sink.ignore

  @Provides
  @Singleton
  def serviceInstancesProvider(configuration: Configuration, mongoComponent: MongoComponent)(implicit
    ec: ExecutionContext
  ): ServiceInstances =
    new DefaultServiceInstances(configuration, mongoComponent)

  @Provides
  @Singleton
  def lockProvider(configuration: Configuration, lockRepo: LockRepository): TimePeriodLockService =
    new TimePeriodLockService {
      val refreshInterval =
        new FiniteDuration(configuration.getMillis(s"microservice.metrics.gauges.interval"), TimeUnit.MILLISECONDS)
      val lockId: String = "email-metrics"
      val ttl: FiniteDuration = refreshInterval.plus(Duration(60 * 1000, MILLISECONDS))
      val lockRepository: LockRepository = lockRepo
    }

  @Provides
  @Singleton
  def metricOrchestratorProvider(
    sources: List[MetricSource],
    exclusiveTimePeriodLock: TimePeriodLockService,
    metricsRepository: MongoMetricRepository,
    metrics: Metrics
  ): MetricOrchestrator = {

    val srcs = sources
      .collect { case metricSource =>
        s"\n${metricSource.toString}"
      }
      .mkString(",")

    logger.warn(s"metricOrchestratorProvider, sources: $srcs")
    new MetricOrchestrator(
      sources,
      LockService(exclusiveTimePeriodLock.lockRepository, exclusiveTimePeriodLock.lockId, exclusiveTimePeriodLock.ttl),
      metricsRepository,
      metrics.defaultRegistry
    )
  }

  @Provides
  @Singleton
  def metricSourcesProvider(scheduledMetrics: ScheduledMetrics): List[MetricSource] =
    scheduledMetrics.sources

  @Provides
  @Singleton
  def lockRepositoryProvider(mongo: MongoComponent, timestampSupport: TimestampSupport)(implicit
    ec: ExecutionContext
  ): LockRepository =
    new MongoLockRepository(mongo, timestampSupport)

  @Provides
  @Singleton
  def senderDomainConfigurationLoader(configuration: Configuration): SenderDomainConfigurationLoader =
    new SenderDomainConfigurationLoader(configuration)

  @Provides
  @Singleton
  def emailEventsRepositoryProvider(
    configuration: Configuration,
    mongo: MongoComponent,
    senderDomainConfigurationLoader: SenderDomainConfigurationLoader
  )(implicit ec: ExecutionContext): EmailEventsRepository =
    new EmailEventsRepository(configuration, mongo)

  @Provides
  @Singleton
  def mongoMetricsRepositoryProvider(mongo: MongoComponent)(implicit ec: ExecutionContext): MongoMetricRepository =
    new MongoMetricRepository(mongo)

  @Provides
  @Singleton
  def dateTimeProvider(): Instant = DateTimeUtils.now

  @Provides
  @Singleton
  def eventHubRepositoryProvider(configuration: Configuration, mongo: MongoComponent)(implicit
    ec: ExecutionContext
  ): EventHubRepository =
    new EventHubRepository(configuration.get[String]("event-hub.collection"), configuration, mongo)

  @Provides
  @Singleton
  def eventHubStreamConfig(configuration: Configuration): EventHubStreamConfig =
    EventHubStreamConfig(
      eventPollingInterval = eventHubStreamPollingIntervalProvider(configuration),
      eventMaxRetries = eventHubStreamMaxRetriesProvider(configuration),
      elements = eventHubStreamElementsProvider(configuration),
      per = eventHubStreamElementsPerProvider(configuration),
      minBackOff = eventHubStreamMinBackOffProvider(configuration),
      maxBackOff = eventHubStreamMaxBackOffProvider(configuration)
    )

  private def eventHubStreamPollingIntervalProvider(configuration: Configuration): FiniteDuration =
    configuration
      .getOptional[FiniteDuration]("streams.event-hub.event-polling-interval")
      .getOrElse(throw new RuntimeException("streams.event-hub.event-polling-interval is not specified"))

  private def eventHubStreamMaxRetriesProvider(configuration: Configuration): Int =
    configuration
      .getOptional[Int]("streams.event-hub.max-retries")
      .getOrElse(throw new RuntimeException("streams.event-hub.max-retries is not specified"))

  private def eventHubStreamElementsProvider(configuration: Configuration): Int =
    configuration
      .getOptional[Int]("streams.event-hub.elements")
      .getOrElse(throw new RuntimeException("streams.event-hub.elements is not specified"))

  private def eventHubStreamElementsPerProvider(configuration: Configuration): FiniteDuration =
    configuration
      .getOptional[FiniteDuration]("streams.event-hub.elements-per")
      .getOrElse(throw new RuntimeException("streams.event-hub.elements-per is not specified"))

  private def eventHubStreamMinBackOffProvider(configuration: Configuration): FiniteDuration =
    configuration
      .getOptional[FiniteDuration]("streams.event-hub.min-back-off")
      .getOrElse(throw new RuntimeException("streams.event-hub.min-back-off is not specified"))

  private def eventHubStreamMaxBackOffProvider(configuration: Configuration): FiniteDuration =
    configuration
      .getOptional[FiniteDuration]("streams.event-hub.max-back-off")
      .getOrElse(throw new RuntimeException("streams.event-hub.max-back-off is not specified"))
}

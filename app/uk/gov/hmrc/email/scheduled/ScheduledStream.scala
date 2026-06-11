/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.scheduled

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source }
import org.apache.pekko.stream.{ KillSwitch, KillSwitches }
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.LockService
import uk.gov.hmrc.email.config.ScheduledJobConfig

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent
import scala.concurrent.{ ExecutionContext, Future }

class ScheduledStream(
  config: ScheduledJobConfig,
  name: String,
  workload: () => Future[Unit],
  sink: Sink[Unit, ?] = Sink.ignore,
  logger: Logger,
  lifecycle: Option[ApplicationLifecycle] = None
)(implicit actorSystem: ActorSystem) {
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  private val killSwitch = new AtomicReference[Option[KillSwitch]](None)

  // Only start if enabled in config
  if (config.taskEnabled) {
    start()
  }

  def start(): Unit = {
    if (!killSwitch.compareAndSet(None, None)) {
      logger.warn(s"$name stream already running")
      return
    }

    logger.warn(s"$name stream starting: initialDelay: ${config.initialDelay}, interval: ${config.interval}")

    val (ks, _) = Source
      .tick(config.initialDelay, config.interval, ())
      .mapAsync(1) { _ =>
        logger.debug(s"TICK - '$name' calling workload")
        workload().recover { case e =>
          logger.error(s"Workload failed: ${e.getMessage}", e)
        }
      }
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()

    killSwitch.set(Some(ks))

    lifecycle.foreach { lc =>
      lc.addStopHook { () =>
        logger.warn(s"$name shutting down stream...")
        stop()
        Future.successful(())
      }
    }
  }

  def stop(): Unit =
    killSwitch.get().foreach(_.shutdown())
}

class ScheduledStreamBuilder(
  config: ScheduledJobConfig,
  name: String,
  sink: Sink[Unit, ?] = Sink.ignore,
  logger: Logger,
  lifecycle: Option[ApplicationLifecycle] = None
)(implicit actorSystem: ActorSystem) {
  private implicit val ec: ExecutionContext = actorSystem.dispatcher

  private var workload: () => Future[Unit] = () => Future.unit
  private var conditional: Option[() => Boolean] = None
  private var lockable: Option[LockService] = None

  def withWorkload(w: => Future[Unit]): this.type = {
    workload = () => w
    this
  }

  def withConditional(c: => Boolean): this.type = {
    conditional = Some(() => c)
    this
  }

  def withLocking(l: LockService): this.type = {
    lockable = Some(l)
    this
  }

  def build(): ScheduledStream = {
    // Compose the final workload function
    val finalWorkload = composeWorkload(workload)
    new ScheduledStream(config, name, finalWorkload, sink, logger, lifecycle)(actorSystem)
  }

  private def composeWorkload(baseWorkload: () => Future[Unit]): () => Future[Unit] = { () =>
    // Apply conditional check first, then execute if needed
    conditional match {
      case Some(cond) if !cond() =>
        logger.debug(s"$name: Condition not met, workload not executing")
        Future.unit
      case _ =>
        // Apply locking if available
        lockable match {
          case Some(lock) =>
            lock
              .withLock {
                logger.debug(s"$name: Executing workload with lock")
                baseWorkload()
              }
              .map {
                case Some(_) =>
                  logger.debug(s"$name: Lock acquired, workload executed")
                  ()
                case None =>
                  logger.warn(s"$name: Failed to acquire lock, skipping workload")
                  ()
              }
          case None =>
            logger.debug(s"$name: Executing workload without lock")
            baseWorkload()
        }
    }
  }
}

object ScheduledStream {
  def builder(
    config: ScheduledJobConfig,
    name: String,
    sink: Sink[Unit, ?] = Sink.ignore,
    logger: Logger,
    lifecycle: Option[ApplicationLifecycle] = None
  )(implicit actorSystem: ActorSystem): ScheduledStreamBuilder =
    new ScheduledStreamBuilder(config, name, sink, logger, lifecycle)
}

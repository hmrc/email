/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.email.services

import cats.effect.{ ContextShift, IO }
import com.google.inject.Singleton
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.email.config.SenderDomainConfigurationLoader
import uk.gov.hmrc.email.connectors.{ ImiConnector, ImiConnectors }
import uk.gov.hmrc.email.emailaddress.EmailAddress
import uk.gov.hmrc.email.model.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.audit.http.connector.AuditConnector
import uk.gov.hmrc.play.audit.model.{ DataEvent, EventTypes }

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.duration.{ DurationLong, FiniteDuration }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success }

@Singleton()
class SuppressionListManagement @Inject() (
  imiConnectors: ImiConnectors,
  conf: SenderDomainConfigurationLoader,
  configuration: Configuration,
  audit: AuditConnector
)(implicit ec: ExecutionContext)
    extends Logging {
  implicit val hc: HeaderCarrier = HeaderCarrier()
  private val configuredDomains = conf.default.keys

  private val suppressionListLife = configuration.get[Int]("suppression-list-expiry-days").toLong
  private val suppressionListPageSize = configuration.get[Int]("suppression-list-page-size").toLong
  private def expiryDate: Instant =
    Instant.now().minus(suppressionListLife, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
  private val throttleDuration: Long = configuration.get[Int]("suppression-list-delete-throttle-duration-millis").toLong
  val system: ActorSystem = ActorSystem("SubscriberPushSubscriptions")
  implicit val materializer: Materializer = Materializer(system)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)

  def clean(domainsOfInterest: List[String]): Future[Unit] =
    configuredDomains
      .foldLeft(IO.pure(())) { (pre, domain) =>
        pre.flatMap { _ =>
          val imiConnector = imiConnectors.all(domain)
          val groupId = conf.default(domain).imi.groupId
          if (domainsOfInterest.contains(domain)) {
            logger.warn(s"startWorkOnDomain $domain")
            startClean(imiConnector, groupId)
          } else {
            logger.warn(s"groupEmptyFor $domain or we are not working on it")
            IO.pure(())
          }
        }
      }
      .unsafeToFuture()

  private def getConsentListEventually(imiConnector: ImiConnector, groupId: String, continueToken: Option[String])(
    implicit ec: ExecutionContext
  ): Future[ConsentItemList] = {
    val promise = Promise[ConsentItemList]()
    val _ = system.scheduler.scheduleOnce(throttleDuration.millis) {
      logger.warn(s"getConsentListEventually duration $throttleDuration")
      imiConnector.getConsentList(groupId, continueToken, suppressionListPageSize).onComplete {
        case Success(value) => promise.success(value)
        case Failure(exception) =>
          logger.error(s"getConsentListFailed ${exception.getMessage}")
          promise.success(ConsentItemList(List.empty[ConsentItem], continueToken))
      }
    }
    promise.future
  }

  private[services] def cleanConsentItems(
    imiConnector: ImiConnector,
    groupId: String,
    continueToken: Option[String]
  ): IO[Unit] =
    IO.defer {
      for {
        consentListItem <- IO.fromFuture(IO(getConsentListEventually(imiConnector, groupId, continueToken)))
        consentList = consentListItem.items
        filteredItems = consentList.filter(_.lastUpdated.compareTo(expiryDate) < 0)
        result <- if (filteredItems.nonEmpty) IO.fromFuture(IO(deleteItems(filteredItems, imiConnector, groupId)))
                  else
                    IO.pure(())
        _ <- if (consentListItem.continueToken.isDefined && consentList.size == suppressionListPageSize)
               cleanConsentItems(imiConnector, groupId, consentListItem.continueToken)
             else IO.pure(result)
      } yield ()
    }

  def startClean(imiConnector: ImiConnector, groupId: String): IO[Unit] =
    cleanConsentItems(imiConnector, groupId, None)

  private def auditSuccess(emailAddress: String, lastUpdated: Instant, details: Map[String, String] = Map.empty) =
    audit.sendEvent(
      DataEvent(
        "email",
        tags = Map("transactionName" -> "DeleteConsentItem"),
        detail = Map(
          "emailAddress"    -> emailAddress,
          "lastUpdatedDate" -> lastUpdated.toString
        ) ++ details,
        auditType = EventTypes.Succeeded
      )
    )

  private def auditFailure(emailAddress: String, lastUpdated: Instant, details: Map[String, String] = Map.empty) =
    audit.sendEvent(
      DataEvent(
        "email",
        tags = Map("transactionName" -> "DeleteConsentItem"),
        detail = Map(
          "emailAddress"    -> emailAddress,
          "lastUpdatedDate" -> lastUpdated.toString
        ) ++ details,
        auditType = EventTypes.Failed
      )
    )

  private[services] def deleteItems(
    items: List[ConsentItem],
    imiConnector: ImiConnector,
    groupId: String
  ): Future[Done] = {
    logger.warn(s"Start consent list deleteItems - ${items.size} items")

    Source(items)
      .throttle(1, FiniteDuration(throttleDuration, TimeUnit.MILLISECONDS))
      .mapAsync(parallelism = 1) { item =>
        imiConnector
          .deleteConsent(EmailAddress(item.address.trim), groupId, oldList = true)
          .map {
            case DeleteConsentSuccess => auditSuccess(item.address, item.lastUpdated)
            case DeleteConsentNotFound =>
              auditSuccess(item.address, item.lastUpdated, Map("result" -> "not_found"))
            case DeleteConsentFailed(statusCode, message) =>
              auditFailure(
                item.address,
                item.lastUpdated,
                Map("error" -> message, "statusCode" -> statusCode.map(_.toString).getOrElse("N/A"))
              )
          }
          .recover { case ex =>
            logger.warn(s"Failed to delete consent item with exception: ${ex.getMessage}")
            ()
          }
      }
      .runWith(Sink.ignore)
      .map { _ =>
        logger.info(s"Completed consent item deletion for ${items.size} items")
        Done
      }
      .recover { case ex =>
        logger.error(s"Capture Stream Failure ${ex.getMessage}")
        Done
      }
  }
}

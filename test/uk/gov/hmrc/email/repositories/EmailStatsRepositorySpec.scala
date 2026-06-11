/*
 * Copyright 2025 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.repositories

import org.mongodb.scala.bson.{ BsonInt32, BsonString }
import org.mongodb.scala.model.Filters
import org.mongodb.scala.{ Document, ObservableFuture, SingleObservableFuture }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.test.Helpers.{ await, defaultAwaitTimeout }
import uk.gov.hmrc.email.SpecBase
import uk.gov.hmrc.email.model.MetricPrefix
import uk.gov.hmrc.email.utils.EmailStatus
import uk.gov.hmrc.mongo.test.MongoSupport
import scala.concurrent.ExecutionContext.Implicits.global

class EmailStatsRepositorySpec
    extends SpecBase with ScalaFutures with MongoSupport with GuiceOneAppPerSuite with IntegrationPatience {

  val emailStatsRepository = new EmailStatsRepository(mongoComponent, configuration = app.configuration)

  "put a new stats record" in new Setup {
    await(emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent))
    await(emailStatsRepository.put(MetricPrefix.FormId, "sa300", EmailStatus.Bounced))
    await(emailStatsRepository.put(MetricPrefix.Domain, "tax.gov.uk", EmailStatus.Bounced))
    private val docs = await(mongoComponent.database.getCollection("email_stats").find(Filters.empty()).toFuture())
    private val result = parsedResult(docs)
    result mustBe (List(
      Some(("formId.p800.sent", 1)),
      Some(("formId.sa300.bounced", 1)),
      Some(("domain.tax.gov.uk.bounced", 1))
    ))
  }

  "put a new stats record will increment count" in new Setup {
    await {
      for {
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "sa300", EmailStatus.Bounced)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "sa300", EmailStatus.Bounced)
      } yield (())

    }
    private val docs = await(mongoComponent.database.getCollection("email_stats").find(Filters.empty()).toFuture())
    private val result = parsedResult(docs)
    result mustBe (List(Some(("formId.p800.sent", 3)), Some(("formId.sa300.bounced", 2))))
  }
  "metrics will fetch records" in new Setup {
    await {
      for {
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "p800", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "ss300", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "sa316", EmailStatus.Sent)
        _ <- emailStatsRepository.put(MetricPrefix.FormId, "sa300", EmailStatus.Bounced)
      } yield (())
    }

    private val docs = await(mongoComponent.database.getCollection("email_stats").find(Filters.empty()).toFuture())
    private val result = parsedResult(docs)
    result mustBe (List(
      Some(("formId.p800.sent", 2)),
      Some(("formId.ss300.sent", 1)),
      Some("formId.sa316.sent", 1),
      Some("formId.sa300.bounced", 1)
    ))
  }

  override def beforeEach(): Unit = {

    super.beforeEach()
    await(emailStatsRepository.collection.deleteMany(Filters.empty()).toFuture())
    val _ = await(emailStatsRepository.ensureIndexes())
  }

  class Setup {
    def parsedResult(docs: Seq[Document]): Seq[Option[(String, Int)]] =
      docs.map { doc =>
        for {
          name  <- doc.collectFirst { case ("name", b: BsonString) => b.getValue }
          count <- doc.collectFirst { case ("count", b: BsonInt32) => b.getValue }
        } yield (name, count)
      }
  }
}

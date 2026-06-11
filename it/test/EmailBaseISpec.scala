/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.{ BeforeAndAfterAll, SuiteMixin, TestSuite }
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.inject.guice.{ GuiceApplicationBuilder, GuiceableModule }
import play.api.libs.json.JsValue
import play.api.{ Application, Environment, Logger, Mode }
import uk.gov.hmrc.email.utils.ImplicitConversions.stringToURL
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{ HeaderCarrier, HttpResponse }
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

trait EmailBaseISpec
    extends SuiteMixin with BeforeAndAfterAll with ScalaFutures with IntegrationPatience with GuiceOneServerPerSuite {
  this: TestSuite =>

  implicit val headerCarrier: HeaderCarrier = new HeaderCarrier()

  private val logger = Logger(getClass)

  override def fakeApplication(): Application = {
    logger.info(s"""Starting application with additional config:
                   |  ${configMap.mkString("\n  ")}
                   |and module overrides:
                   |  ${additionalOverrides.mkString("[", "\n", "]")}""".stripMargin)
    // If applicationMode is not set, use Mode.Test (the default for GuiceApplicationBuilder)
    GuiceApplicationBuilder(environment = Environment.simple(mode = applicationMode.getOrElse(Mode.Test)))
      .configure(configMap)
      .overrides(additionalOverrides*)
      .build()
  }

  lazy val httpClient: HttpClientV2 = app.injector.instanceOf[HttpClientV2]

  def resetEmailsSent: Int =
    httpClient.get(resetEmailsSentUri).execute[HttpResponse].futureValue.status

  def sentEmails: Seq[JsValue] =
    httpClient.get(getEmailsSentUri).execute[HttpResponse].futureValue.json.as[Seq[JsValue]]

  def sentEmails(email: String): Seq[JsValue] =
    httpClient.get(getEmailsSentUri(email)).execute[HttpResponse].futureValue.json.as[Seq[JsValue]]

  def resetContactPolicyStub() =
    httpClient
      .delete("http://localhost:8185/digital-contact-stub/imi/v1/contactpolicy")
      .execute[HttpResponse]
      .futureValue
      .status

  def additionalConfig: Map[String, ?] =
    Map.empty

  def additionalOverrides: Seq[GuiceableModule] =
    Seq.empty

  def testName: String =
    getClass.getSimpleName

  // If applicationMode is set, default to Mode.Dev, to preserve earlier behaviour
  def applicationMode: Option[Mode] =
    Some(Mode.Dev)

  protected val testId =
    TestId(testName)

  // This is not called mongoUri to avoid conflicts with mongo testing traits.
  protected def serviceMongoUri =
    s"mongodb://localhost:27017/${testId.toString}"

  private lazy val mongoConfig =
    Map(s"mongodb.uri" -> serviceMongoUri)

  private lazy val configMap =
    mongoConfig ++
      additionalConfig

  def resource(path: String): String =
    s"http://localhost:$port/${format(path)}"

  def hostResource(name: String, path: String): String =
    s"http://${host(name)}/$path"

  def host(name: String): String = {
    val host = additionalConfig(s"microservice.services.$name.host")
    val port = additionalConfig(s"microservice.services.$name.port")
    s"http://$host:$port"
  }

  val mailgunResetUri =
    s"${host("mailgun")}/mailgun/reset"

  def getEmailsSentUri(email: String): URL =
    s"${host("imi")}/digital-contact-stub/imi/messages/email/$email"

  def getEmailsSentUri: URL =
    s"${host("imi")}/digital-contact-stub/imi/messages"

  def resetEmailsSentUri: URL =
    s"${host("imi")}/digital-contact-stub/imi/reset"

  def loadConsentItemUri: URL =
    s"${host("imi")}/digital-contact-stub/imi/v1/groups/123456789/members"

  def getConsentItemUri(email: String): URL =
    s"${host("imi")}/digital-contact-stub/imi/v1/groups/123456789/members?address=$email"

  def mailgunEmailUri(domain: String): URL =
    s"${host("mailgun")}/mailgun/messages?domain=$domain"

  def mailgunEventsUri(domain: String): URL =
    s"${host("mailgun")}/v3/$domain/events"

  def format(uri: String): String = if (uri.startsWith("/")) uri.drop(1) else uri
}

case class TestId(testName: String) {

  val runId =
    DateTimeFormatter.ofPattern("HHmmssSSS").format(LocalDateTime.now())

  override val toString =
    s"${testName.toLowerCase.take(30)}-$runId"
}

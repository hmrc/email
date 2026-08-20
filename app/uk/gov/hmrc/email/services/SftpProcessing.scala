/*
 * Copyright 2026 HM Revenue & Customs
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

import com.jcraft.jsch.{ ChannelSftp, JSch, ProxyHTTP }
import play.api.libs.json.Json
import play.api.{ Configuration, Logging }
import uk.gov.hmrc.email.controllers.model.Event
import uk.gov.hmrc.email.model.{ EventPayload, RawEvent }
import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.matching.Regex
import scala.util.{ Failure, Success, Try }

@Singleton
class SftpProcessing @Inject() (configuration: Configuration, eventProcessing: EventProcessing)(implicit
  ec: ExecutionContext
) extends Logging {

  private def scrapeEmails(text: String) = {
    val emailRegex: Regex = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b".r
    emailRegex.findAllIn(text).toList.foldLeft(text)((acc, email) => acc.replace(email, "emailHidden"))
  }

  def processFiles: Future[Int] = {
    logger.warn(s"SftpReadJobCalled")
    Try {
      val fileNames =
        channel
          .ls(SftpConfiguration.path)
          .iterator()
          .asScala
          .toList
          .map(_.asInstanceOf[ChannelSftp#LsEntry].getFilename)
          .sorted

      logger.warn(s"SFTP files found ${fileNames.size}")

      fileNames.foreach { fileName =>
        if (!fileName.startsWith("processed_")) {
          logger.warn(s"Reading SFTP file path ${SftpConfiguration.path + fileName}")

          val fileInputStream = channel.get(SftpConfiguration.path + fileName)
          val chunkSize = 10000 // Define your desired chunk size

          val buffer = new Array[Byte](chunkSize)
          var bytesRead = fileInputStream.read(buffer)

          var jsonBuffer = ""
          while (bytesRead != -1) {
            val chunk = new String(buffer, 0, bytesRead)
            jsonBuffer += chunk

            decodedJson(jsonBuffer, fileName) match {
              case Right(json) =>
                logger.warn(
                  s"Events in a SFTP file ${SftpConfiguration.path + fileName} with size ${json.size} ready to process"
                )
                json.foreach { payload =>
                  logger.warn(
                    s"Event in a SFTP file ${SftpConfiguration.path + fileName} with transId ${payload.df_payload.deliveryInfoNotification.transId} ready to process"
                  )
                  val rawEvent = payload.df_payload
                  val event = parseEvent(rawEvent)
                  logger.warn(s"SFTPEventProcessedFor ${event.messageId}")
                  eventProcessing(event, "SFTP").map(_ => ()).recover { case e: Exception =>
                    // TODO: may be we can attempt to try to save again
                    logger.error(
                      s"Failed to save SFTP event for transId ${payload.df_payload.deliveryInfoNotification.transId} ${e.getMessage}"
                    )
                  }
                  jsonBuffer = ""
                }
              case Left(error) =>
                logger.error(s"Failed to decode JSON ${SftpConfiguration.path + fileName} $error")

            }
            bytesRead = fileInputStream.read(buffer)
          }
          channel.rename(SftpConfiguration.path + fileName, s"${SftpConfiguration.path}processed_$fileName")
        }
      }
    } match {
      case Success(_)         => logger.warn(s"Sftp files events processed successfully")
      case Failure(exception) => logger.warn(s"SftpError ${exception.getMessage}")
    }

    object SftpConfiguration {
      val host = "sftp-uk.imimobile.net"
      val username = "HMRC_FailedResponses"
      val password: String = configuration.get[String]("imi.sftpKey")
      val pathFolder: String = configuration.get[String]("imi.sftpPath")
      val path = s"/$pathFolder/Retried_and_Failed/"
    }

    def channel = {
      val jsch = new JSch()

      val session = jsch.getSession(SftpConfiguration.username, SftpConfiguration.host, 22)
      val proxy = new ProxyHTTP("outbound-proxy-vip", 3128)
      proxy.setUserPasswd("email", configuration.get[String]("proxy.password"))

      session.setProxy(proxy)
      session.setPassword(SftpConfiguration.password)
      session.setConfig("StrictHostKeyChecking", "no")
      session.setConfig("cipher.s2c", "aes128-cbc")
      session.connect()

      logger.warn(s"Sftp Connection status is ${session.isConnected}")

      val channel = session.openChannel("sftp").asInstanceOf[ChannelSftp]
      channel.connect()
      channel
    }

    def decodedJson(jsonBuffer: String, fileName: String) =
      Try(Json.parse(jsonBuffer).as[Seq[EventPayload]]) match {
        case Success(value) => Right(value)
        case Failure(e) =>
          logger.warn(s"FailedJSON for ${SftpConfiguration.path + fileName}  ${scrapeEmails(jsonBuffer)}")
          Left(e.toString)
      }

    Future.successful(1)
  }

  private def parseEvent(rawEvent: RawEvent) =
    Event(
      rawEvent.deliveryInfoNotification.transId,
      rawEvent.deliveryInfoNotification.correlationId,
      rawEvent.deliveryInfoNotification.deliveryInfo.deliveryStatus,
      rawEvent.deliveryInfoNotification.deliveryInfo.timeStamp,
      rawEvent.deliveryInfoNotification.deliveryInfo.code,
      rawEvent.deliveryInfoNotification.deliveryInfo.description,
      rawEvent.deliveryInfoNotification.deliveryInfo.additionalInfo,
      rawEvent.deliveryInfoNotification.deliveryInfo.destination,
      Map.empty
    )
}

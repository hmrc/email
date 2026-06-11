/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.email.utils

import java.net.{ URI, URL, URLEncoder }
import scala.util.Try

object ImplicitConversions {
  given stringToURL: Conversion[String, URL] with {
    def apply(s: String): URL = Try {
      URI.create(s).toURL
    }.getOrElse(throw new IllegalArgumentException(s"Invalid URL: $s"))
  }
  given seqToQueryString: Conversion[Seq[(String, String)], String] with {
    def apply(seq: Seq[(String, String)]): String = {
      val paramPairs = seq.map { case (k, v) => s"$k=${URLEncoder.encode(v, "utf-8")}" }
      if (paramPairs.isEmpty) "" else paramPairs.mkString("?", "&", "")
    }
  }
}

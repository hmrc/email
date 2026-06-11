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

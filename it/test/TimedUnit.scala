/*
 * Copyright 2024 HM Revenue & Customs
 *
 */

package test

import org.apache.commons.lang3.time.StopWatch

trait TimedUnit {
  def timed(f: => Unit): Long = {
    val stopWatch = new StopWatch()
    stopWatch.start()
    f
    stopWatch.stop()
    stopWatch.getTime
  }
}

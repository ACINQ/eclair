/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.eclair.channel.Helpers.Funding
import org.scalatest.FunSuite

import scala.compat.Platform
import scala.concurrent.duration._

class HelpersSpec extends FunSuite {

  test("compute funding timeout") {
    // we are supposed to wait 2 hours, and have already been waiting 1 hour, we have one more hour to wait
    assert(Funding.computeFundingTimeout(
      now = 7200,
      waitingSince = 3600,
      delay = 2 hours,
      minDelay = 10 minutes) === (1 hour))

    // we are supposed to wait 2 hours, and have already been waiting 9 hours, we'll wait the minimum of 10 minutes
    assert(Funding.computeFundingTimeout(
      now = 36000,
      waitingSince = 3600,
      delay = 2 hours,
      minDelay = 10 minutes) === (10 minutes))

    // we are supposed to wait 5 minutes, and have already been waiting 4 minutes, we'll wait the minimum of 10 minutes
    assert(Funding.computeFundingTimeout(
      now = 1240,
      waitingSince = 1000,
      delay = 5 minutes,
      minDelay = 10 minutes) === (10 minutes))
  }

  test("compute refresh delay") {
    import org.scalatest.Matchers._
    implicit val log = akka.event.NoLogging
    Helpers.nextChannelUpdateRefresh(1544400000).toSeconds should equal (0)
    Helpers.nextChannelUpdateRefresh((Platform.currentTime.milliseconds - 9.days).toSeconds).toSeconds should equal (24 * 3600L +- 100)
    Helpers.nextChannelUpdateRefresh((Platform.currentTime.milliseconds - 3.days).toSeconds).toSeconds should equal (7 * 24 * 3600L +- 100)
    Helpers.nextChannelUpdateRefresh(Platform.currentTime.milliseconds.toSeconds).toSeconds should equal (10 * 24 * 3600L +- 100)

  }

}


/*
 * Copyright 2020 ACINQ SAS
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

import kamon.Kamon

object Monitoring {

  object Metrics {
    val ChannelsCount = Kamon.gauge("channels.count")
    val ChannelErrors = Kamon.counter("channels.errors")
    val ChannelLifecycleEvents = Kamon.counter("channels.lifecycle")
  }

  object Tags {
    val Event = "event"
    val Fatal = "fatal"
    val Origin = "origin"
    val State = "state"

    object Events {
      val Created = "created"
      val Closing = "closing"
      val Closed = "closed"
    }

    object Origins {
      val Local = "local"
      val Remote = "remote"
    }

  }

}

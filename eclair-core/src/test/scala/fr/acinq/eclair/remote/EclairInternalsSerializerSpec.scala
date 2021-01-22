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

package fr.acinq.eclair.remote

import fr.acinq.eclair.router.Router.GossipDecision
import org.scalatest.funsuite.AnyFunSuite

class EclairInternalsSerializerSpec extends AnyFunSuite {

  test("canary test codec gossip decision") {
    
    def codec(d: GossipDecision) = d match {
      case _: GossipDecision.Accepted => ()
      case _: GossipDecision.Duplicate => ()
      case _: GossipDecision.InvalidSignature => ()
      case _: GossipDecision.NoKnownChannel => ()
      case _: GossipDecision.ValidationFailure => ()
      case _: GossipDecision.InvalidAnnouncement => ()
      case _: GossipDecision.ChannelPruned => ()
      case _: GossipDecision.ChannelClosing => ()
      case _: GossipDecision.ChannelClosed => ()
      case _: GossipDecision.Stale => ()
      case _: GossipDecision.NoRelatedChannel => ()
      case _: GossipDecision.RelatedChannelPruned => ()
      // NB: if a new gossip decision is added, this pattern matching will fail
      // this serves as a reminder that a new codec is to be added in EclairInternalsSerializer.codec
    }

  }

}

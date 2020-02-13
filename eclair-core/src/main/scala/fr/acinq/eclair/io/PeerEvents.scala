/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.io

import akka.actor.ActorRef
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.PayToOpenRequest

import scala.concurrent.Promise

sealed trait PeerEvent

case class PeerConnected(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

case class PeerDisconnected(peer: ActorRef, nodeId: PublicKey) extends PeerEvent

/**
 * @param expireAt unix timestamp, this request will be cancelled at that time
 */
case class PayToOpenRequestEvent(peer: ActorRef, payToOpenRequest: PayToOpenRequest, decision: Promise[Boolean])
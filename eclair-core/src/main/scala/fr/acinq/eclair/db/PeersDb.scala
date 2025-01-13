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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol.{NodeAddress, NodeInfo}
import fr.acinq.eclair.{Features, InitFeature, TimestampSecond}
import scodec.bits.ByteVector

/** The PeersDb contains information about our direct peers, with whom we have or had channels. */
trait PeersDb {

  /** Update our DB with a verified address and features for the given peer. */
  def addOrUpdatePeer(nodeId: PublicKey, address: NodeAddress, features: Features[InitFeature]): Unit

  /** Update our DB with the features for the given peer, without updating its address. */
  def addOrUpdatePeerFeatures(nodeId: PublicKey, features: Features[InitFeature]): Unit

  def removePeer(nodeId: PublicKey): Unit

  def getPeer(nodeId: PublicKey): Option[NodeInfo]

  def listPeers(): Map[PublicKey, NodeInfo]

  def addOrUpdateRelayFees(nodeId: PublicKey, fees: RelayFees): Unit

  def getRelayFees(nodeId: PublicKey): Option[RelayFees]

  /** Update our peer's blob data when [[fr.acinq.eclair.Features.ProvideStorage]] is enabled. */
  def updateStorage(nodeId: PublicKey, data: ByteVector): Unit

  /** Get the last blob of data we stored for that peer, if [[fr.acinq.eclair.Features.ProvideStorage]] is enabled. */
  def getStorage(nodeId: PublicKey): Option[ByteVector]

  /** Remove storage from peers that have had no active channel with us for a while. */
  def removePeerStorage(peerRemovedBefore: TimestampSecond): Unit

}

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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._

// @formatter:off
case class LocalChanges(proposed: List[UpdateMessage], signed: List[UpdateMessage], acked: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class RemoteChanges(proposed: List[UpdateMessage], acked: List[UpdateMessage], signed: List[UpdateMessage]) {
  def all: List[UpdateMessage] = proposed ++ signed ++ acked
}
case class HtlcTxAndRemoteSig(htlcTx: HtlcTx, remoteSig: ByteVector64)
case class CommitTxAndRemoteSig(commitTx: CommitTx, remoteSig: ByteVector64)
case class LocalCommit(index: Long, spec: CommitmentSpec, commitTxAndRemoteSig: CommitTxAndRemoteSig, htlcTxsAndRemoteSigs: List[HtlcTxAndRemoteSig])
case class RemoteCommit(index: Long, spec: CommitmentSpec, txid: ByteVector32, remotePerCommitmentPoint: PublicKey)
case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long)
// @formatter:on

// @formatter:off
trait AbstractCommitments {
  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc]
  def localNodeId: PublicKey
  def remoteNodeId: PublicKey
  def capacity: Satoshi
  def availableBalanceForReceive: MilliSatoshi
  def availableBalanceForSend: MilliSatoshi
  def originChannels: Map[Long, Origin]
  def channelId: ByteVector32
  def announceChannel: Boolean
}
// @formatter:on

/**
 * about remoteNextCommitInfo:
 * we either:
 * - have built and signed their next commit tx with their next revocation hash which can now be discarded
 * - have their next per-commitment point
 * So, when we've signed and sent a commit message and are waiting for their revocation message,
 * theirNextCommitInfo is their next commit tx. The rest of the time, it is their next per-commitment point
 */
case class Commitments(channelId: ByteVector32,
                       channelConfig: ChannelConfig,
                       channelFeatures: ChannelFeatures,
                       localParams: LocalParams, remoteParams: RemoteParams,
                       channelFlags: ChannelFlags,
                       localCommit: LocalCommit, remoteCommit: RemoteCommit,
                       localChanges: LocalChanges, remoteChanges: RemoteChanges,
                       localNextHtlcId: Long, remoteNextHtlcId: Long,
                       originChannels: Map[Long, Origin], // for outgoing htlcs relayed through us, details about the corresponding incoming htlcs
                       remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
                       localFundingStatus: LocalFundingStatus,
                       remoteFundingStatus: RemoteFundingStatus,
                       remotePerCommitmentSecrets: ShaChain) extends AbstractCommitments {

  def nextRemoteCommit_opt: Option[RemoteCommit] = remoteNextCommitInfo.swap.toOption.map(_.nextRemoteCommit)

  def params: Params = Params(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags)

  def common: Common = Common(localChanges, remoteChanges, localNextHtlcId, remoteNextHtlcId, localCommit.index, remoteCommit.index, originChannels, remoteNextCommitInfo.swap.map(waitingForRevocation => WaitForRev(waitingForRevocation.sent, waitingForRevocation.sentAfterLocalCommitIndex)).swap, remotePerCommitmentSecrets)

  def commitment: Commitment = Commitment(localFundingStatus, remoteFundingStatus, localCommit, remoteCommit, remoteNextCommitInfo.swap.map(_.nextRemoteCommit).toOption)

  def commitInput: InputInfo = commitment.commitInput

  def fundingTxId: ByteVector32 = commitment.fundingTxId

  def commitmentFormat: CommitmentFormat = params.commitmentFormat

  def channelType: SupportedChannelType = params.channelType

  def localNodeId: PublicKey = params.localNodeId

  def remoteNodeId: PublicKey = params.remoteNodeId

  def announceChannel: Boolean = params.announceChannel

  def capacity: Satoshi = commitment.capacity

  def maxHtlcAmount: MilliSatoshi = params.maxHtlcAmount

  def localChannelReserve: Satoshi = commitment.localChannelReserve(params)

  def remoteChannelReserve: Satoshi = commitment.remoteChannelReserve(params)

  def availableBalanceForSend: MilliSatoshi = commitment.availableBalanceForSend(params, common)

  def availableBalanceForReceive: MilliSatoshi = commitment.availableBalanceForReceive(params, common)

  def getOutgoingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitment.getOutgoingHtlcCrossSigned(htlcId)

  def getIncomingHtlcCrossSigned(htlcId: Long): Option[UpdateAddHtlc] = commitment.getIncomingHtlcCrossSigned(htlcId)

  def fullySignedLocalCommitTx(keyManager: ChannelKeyManager): CommitTx = commitment.fullySignedLocalCommitTx(params, keyManager)

  def specs2String: String = {
    s"""specs:
       |localcommit:
       |  toLocal: ${localCommit.spec.toLocal}
       |  toRemote: ${localCommit.spec.toRemote}
       |  htlcs:
       |${localCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |remotecommit:
       |  toLocal: ${remoteCommit.spec.toLocal}
       |  toRemote: ${remoteCommit.spec.toRemote}
       |  htlcs:
       |${remoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")}
       |next remotecommit:
       |  toLocal: ${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toLocal).getOrElse("N/A")}
       |  toRemote: ${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.toRemote).getOrElse("N/A")}
       |  htlcs:
       |${remoteNextCommitInfo.left.toOption.map(_.nextRemoteCommit.spec.htlcs.map(h => s"    ${h.direction} ${h.add.id} ${h.add.cltvExpiry}").mkString("\n")).getOrElse("N/A")}""".stripMargin
  }

}

object Commitments {

  /** A 1:1 conversion helper to facilitate migration, nothing smart here. */
  def apply(params: Params, common: Common, commitment: Commitment): Commitments = Commitments(
    channelId = params.channelId,
    channelConfig = params.channelConfig,
    channelFeatures = params.channelFeatures,
    localParams = params.localParams,
    remoteParams = params.remoteParams,
    channelFlags = params.channelFlags,
    localCommit = commitment.localCommit,
    remoteCommit = commitment.remoteCommit,
    localChanges = common.localChanges,
    remoteChanges = common.remoteChanges,
    localNextHtlcId = common.localNextHtlcId,
    remoteNextHtlcId = common.remoteNextHtlcId,
    originChannels = common.originChannels,
    remoteNextCommitInfo = common.remoteNextCommitInfo.swap.map(waitForRev => WaitingForRevocation(commitment.nextRemoteCommit_opt.get, waitForRev.sent, waitForRev.sentAfterLocalCommitIndex)).swap,
    localFundingStatus = commitment.localFundingStatus,
    remoteFundingStatus = commitment.remoteFundingStatus,
    remotePerCommitmentSecrets = common.remotePerCommitmentSecrets
  )

}

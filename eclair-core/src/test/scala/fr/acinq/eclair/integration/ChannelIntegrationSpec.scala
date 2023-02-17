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

package fr.acinq.eclair.integration

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, BtcDouble, ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, computeBIP84Address}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.io.{Peer, PeerConnection, Switchboard}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.receive.{ForwardHandler, PaymentHandler}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, TxOwner}
import fr.acinq.eclair.transactions.{OutgoingHtlc, Scripts, Transactions}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, randomBytes32}
import org.json4s.JsonAST.{JString, JValue}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by t-bast on 21/09/2020.
 */

abstract class ChannelIntegrationSpec extends IntegrationSpec {

  def awaitAnnouncements(channels: Int): Unit = {
    val sender = TestProbe()
    awaitCond({
      sender.send(nodes("A").router, Router.GetChannels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == channels
    }, max = 60 seconds, interval = 1 second)
    awaitCond({
      sender.send(nodes("A").router, Router.GetChannelUpdates)
      sender.expectMsgType[Iterable[ChannelUpdate]].size == 2 * channels
    }, max = 60 seconds, interval = 1 second)
  }

  def listReceivedByAddress(address: String, sender: TestProbe = TestProbe()): Seq[ByteVector32] = {
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue]
    res.filter(_ \ "address" == JString(address)).flatMap(_ \ "txids" \\ classOf[JString]).map(ByteVector32.fromValidHex)
  }

  /** Wait for the given transaction to be either in the mempool or confirmed. */
  def waitForTxBroadcastOrConfirmed(txid: ByteVector32, bitcoinClient: BitcoinCoreClient, sender: TestProbe): Unit = {
    awaitCond({
      bitcoinClient.getMempool().pipeTo(sender.ref)
      val inMempool = sender.expectMsgType[Seq[Transaction]].exists(_.txid == txid)
      bitcoinClient.getTxConfirmations(txid).pipeTo(sender.ref)
      val confirmed = sender.expectMsgType[Option[Int]].nonEmpty
      inMempool || confirmed
    }, max = 30 seconds, interval = 1 second)
  }

  /** Wait for the given outpoint to be spent (either by a mempool or confirmed transaction). */
  def waitForOutputSpent(outpoint: OutPoint, bitcoinClient: BitcoinCoreClient, sender: TestProbe): Unit = {
    awaitCond({
      bitcoinClient.isTransactionOutputSpendable(outpoint.txid, outpoint.index.toInt, includeMempool = true).pipeTo(sender.ref)
      val isSpendable = sender.expectMsgType[Boolean]
      !isSpendable
    }, max = 30 seconds, interval = 1 second)
  }

  /** Disconnect node C from a given F node. */
  def disconnectCF(channelId: ByteVector32, sender: TestProbe = TestProbe()): Unit = {
    val (stateListenerC, stateListenerF) = (TestProbe(), TestProbe())
    nodes("C").system.eventStream.subscribe(stateListenerC.ref, classOf[ChannelStateChanged])
    nodes("F").system.eventStream.subscribe(stateListenerF.ref, classOf[ChannelStateChanged])

    sender.send(nodes("F").switchboard, Switchboard.GetPeers)
    val peers = sender.expectMsgType[Iterable[ActorRef]]
    // F's only node is C
    peers.head ! Peer.Disconnect(nodes("C").nodeParams.nodeId)

    // we then wait for F to be in disconnected state
    Seq(stateListenerC, stateListenerF).foreach(listener => awaitCond({
      val channelState = listener.expectMsgType[ChannelStateChanged]
      channelState.currentState == OFFLINE && channelState.channelId == channelId
    }, max = 20 seconds, interval = 1 second))
  }

  case class ForceCloseFixture(sender: TestProbe, paymentSender: TestProbe, stateListenerC: TestProbe, stateListenerF: TestProbe, paymentId: UUID, htlc: UpdateAddHtlc, preimage: ByteVector32, minerAddress: String, finalAddressC: String, finalAddressF: String)

  /** Prepare a C <-> F channel for a force-close test by adding an HTLC that will be hodl-ed at F. */
  def prepareForceCloseCF(commitmentFormat: Transactions.CommitmentFormat): ForceCloseFixture = {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(minerAddress) = sender.expectMsgType[JValue]
    // we subscribe to channel state transitions
    val stateListenerC = TestProbe()
    val stateListenerF = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListenerC.ref, classOf[ChannelStateChanged])
    nodes("F").system.eventStream.subscribe(stateListenerF.ref, classOf[ChannelStateChanged])
    // we create and announce a channel between C and F; we use push_msat to ensure both nodes have an output in the commit tx
    connect(nodes("C"), nodes("F"), 5000000 sat, 500000000 msat)
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED, max = 30 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED, max = 30 seconds)
    generateBlocks(1, Some(minerAddress))
    // the funder sends its channel_ready after only one block
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == WAIT_FOR_CHANNEL_READY, max = 30 seconds)
    generateBlocks(2, Some(minerAddress))
    // the fundee sends its channel_ready after 3 blocks
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == NORMAL, max = 30 seconds)
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == NORMAL, max = 30 seconds)
    // we generate more blocks for the funding tx to be deeply buried and the channel to be announced
    generateBlocks(3, Some(minerAddress))
    awaitAnnouncements(2)
    // first we make sure we are in sync with current blockchain height
    val currentBlockHeight = getBlockHeight()
    awaitCond(getBlockHeight() == currentBlockHeight, max = 20 seconds, interval = 1 second)
    // we use this to control when to fulfill htlcs
    val htlcReceiver = TestProbe()
    nodes("F").paymentHandler ! new ForwardHandler(htlcReceiver.ref)
    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentSender = TestProbe()
    val paymentReq = SendPaymentToNode(paymentSender.ref, 100000000 msat, Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, nodes("F").nodeParams.privateKey, Left("test"), finalCltvExpiryDelta), maxAttempts = 1, routeParams = integrationTestRouteParams)
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    val paymentId = paymentSender.expectMsgType[UUID]
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[IncomingPaymentPacket.FinalPacket](max = 60 seconds).add
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val dataC = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data
    assert(dataC.commitments.params.commitmentFormat == commitmentFormat)
    val finalAddressC = computeBIP84Address(nodes("C").wallet.getP2wpkhPubkey(false), Block.RegtestGenesisBlock.hash)
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val dataF = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data
    assert(dataF.commitments.params.commitmentFormat == commitmentFormat)
    val finalAddressF = computeBIP84Address(nodes("F").wallet.getP2wpkhPubkey(false), Block.RegtestGenesisBlock.hash)
    ForceCloseFixture(sender, paymentSender, stateListenerC, stateListenerF, paymentId, htlc, preimage, minerAddress, finalAddressC, finalAddressF)
  }

  def testDownstreamFulfillLocalCommit(commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    val previouslyReceivedByF = listReceivedByAddress(finalAddressF, sender)
    // we then kill the connection between C and F
    disconnectCF(htlc.channelId, sender)
    // we then have C unilaterally close the channel (which will make F redeem the htlc onchain)
    sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_FORCECLOSE(sender.ref)))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    // we then wait for F to detect the unilateral close and go to CLOSING state
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(3, Some(minerAddress))
    // we then fulfill the htlc, which will make F redeem it on-chain
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we don't need to generate blocks to confirm the htlc-success; C should extract the preimage as soon as it enters
    // the mempool and fulfill the payment upstream.
    paymentSender.expectMsgType[PaymentSent](max = 60 seconds)
    // we then generate enough blocks so that nodes get their main delayed output
    generateBlocks(25, Some(minerAddress))
    // F should have 2 recv transactions: the redeemed htlc and its main output
    // C should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF)
      (receivedByF diff previouslyReceivedByF).size == 2 && (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

  def testDownstreamFulfillRemoteCommit(commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    val previouslyReceivedByF = listReceivedByAddress(finalAddressF, sender)
    // we then kill the connection between C and F
    disconnectCF(htlc.channelId, sender)
    // then we have F unilaterally close the channel
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_FORCECLOSE(sender.ref)))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(3, Some(minerAddress))
    // we then fulfill the htlc (it won't be sent to C, and will be used to pull funds on-chain)
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we don't need to generate blocks to confirm the htlc-success; C should extract the preimage as soon as it enters
    // the mempool and fulfill the payment upstream.
    paymentSender.expectMsgType[PaymentSent](max = 60 seconds)
    // we then generate enough blocks so that F gets its htlc-success delayed output
    generateBlocks(25, Some(minerAddress))
    // F should have 2 recv transactions: the redeemed htlc and its main output
    // C should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      (receivedByF diff previouslyReceivedByF).size == 2 && (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

  def testDownstreamTimeoutLocalCommit(commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    val previouslyReceivedByF = listReceivedByAddress(finalAddressF, sender)
    // we then kill the connection between C and F; otherwise F would send an error message to C when it detects the htlc
    // timeout. When that happens C would broadcast his commit tx, and if it gets to the mempool before F's commit tx we
    // won't be testing the right scenario.
    disconnectCF(htlc.channelId, sender)
    // we generate enough blocks to reach the htlc timeout
    generateBlocks((htlc.cltvExpiry.blockHeight - getBlockHeight()).toInt, Some(minerAddress))
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val Some(localCommit) = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_CLOSING]].data.localCommitPublished
    // we wait until the commit tx has been broadcast
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    waitForTxBroadcastOrConfirmed(localCommit.commitTx.txid, bitcoinClient, sender)
    // we generate a few blocks to get the commit tx confirmed
    generateBlocks(3, Some(minerAddress))
    // we wait until the htlc-timeout has been broadcast
    assert(localCommit.htlcTxs.size == 1)
    waitForOutputSpent(localCommit.htlcTxs.keys.head, bitcoinClient, sender)
    // we generate more blocks for the htlc-timeout to reach enough confirmations
    generateBlocks(3, Some(minerAddress))
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](max = 60 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash == htlc.paymentHash)
    assert(failed.failures.nonEmpty)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure()))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(25, Some(minerAddress))
    // C should have 2 recv transactions: its main output and the htlc timeout
    // F should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      (receivedByF diff previouslyReceivedByF).size == 1 && (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

  def testDownstreamTimeoutRemoteCommit(commitmentFormat: Transactions.CommitmentFormat): Unit = {
    val forceCloseFixture = prepareForceCloseCF(commitmentFormat)
    import forceCloseFixture._

    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    val previouslyReceivedByF = listReceivedByAddress(finalAddressF, sender)
    // we then kill the connection between C and F to ensure the close can only be detected on-chain
    disconnectCF(htlc.channelId, sender)
    // we ask F to unilaterally close the channel
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_FORCECLOSE(sender.ref)))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    // we wait for C to detect the unilateral close
    awaitCond({
      sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
      sender.expectMsgType[RES_GET_CHANNEL_DATA[ChannelData]].data match {
        case d: DATA_CLOSING if d.remoteCommitPublished.nonEmpty => true
        case _ => false
      }
    }, max = 30 seconds, interval = 1 second)
    sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], htlc.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val Some(remoteCommit) = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_CLOSING]].data.remoteCommitPublished
    // we generate enough blocks to make the htlc timeout
    generateBlocks((htlc.cltvExpiry.blockHeight - getBlockHeight()).toInt, Some(minerAddress))
    // we wait until the claim-htlc-timeout has been broadcast
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    assert(remoteCommit.claimHtlcTxs.size == 1)
    waitForOutputSpent(remoteCommit.claimHtlcTxs.keys.head, bitcoinClient, sender)
    // and we generate blocks for the claim-htlc-timeout to reach enough confirmations
    generateBlocks(3, Some(minerAddress))
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](max = 60 seconds)
    assert(failed.id == paymentId)
    assert(failed.paymentHash == htlc.paymentHash)
    assert(failed.failures.nonEmpty)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure()))
    // we then generate enough blocks to confirm all delayed transactions
    generateBlocks(25, Some(minerAddress))
    // C should have 2 recv transactions: its main output and the htlc timeout
    // F should have 1 recv transaction: its main output
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      val receivedByF = listReceivedByAddress(finalAddressF, sender)
      (receivedByF diff previouslyReceivedByF).size == 1 && (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make tx confirm
    generateBlocks(2, Some(minerAddress))
    // and we wait for the channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitCond(stateListenerF.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

  case class RevokedCommitFixture(sender: TestProbe, stateListenerC: TestProbe, revokedCommitTx: Transaction, htlcSuccess: Seq[Transaction], htlcTimeout: Seq[Transaction], finalAddressC: String)

  def testRevokedCommit(commitmentFormat: Transactions.CommitmentFormat): RevokedCommitFixture = {
    val sender = TestProbe()
    // we create and announce a channel between C and F; we use push_msat to ensure F has a balance
    connect(nodes("C"), nodes("F"), 5000000 sat, 300000000 msat)
    generateBlocks(6)
    awaitAnnouncements(2)
    // we subscribe to C's channel state transitions
    val stateListenerC = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListenerC.ref, classOf[ChannelStateChanged])
    // we use this to get commitments
    val sigListener = TestProbe()
    nodes("F").system.eventStream.subscribe(sigListener.ref, classOf[ChannelSignatureReceived])
    // we use this to control when to fulfill htlcs
    val forwardHandlerC = TestProbe()
    nodes("C").paymentHandler ! new ForwardHandler(forwardHandlerC.ref)
    val forwardHandlerF = TestProbe()
    nodes("F").paymentHandler ! new ForwardHandler(forwardHandlerF.ref)
    // this is the actual payment handler that we will forward requests to
    val paymentHandlerC = nodes("C").system.actorOf(PaymentHandler.props(nodes("C").nodeParams, nodes("C").register))
    val paymentHandlerF = nodes("F").system.actorOf(PaymentHandler.props(nodes("F").nodeParams, nodes("F").register))
    // first we make sure nodes are in sync with current blockchain height
    val currentBlockHeight = getBlockHeight()
    awaitCond(getBlockHeight() == currentBlockHeight, max = 20 seconds, interval = 1 second)

    // we now send a few htlcs C->F and F->C in order to obtain a commitments with multiple htlcs
    def send(amountMsat: MilliSatoshi, paymentHandler: ActorRef, paymentInitiator: ActorRef): UUID = {
      sender.send(paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, maxAttempts = 1, routeParams = integrationTestRouteParams)
      sender.send(paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
    }

    val buffer = TestProbe()
    send(100000000 msat, paymentHandlerF, nodes("C").paymentInitiator)
    forwardHandlerF.expectMsgType[IncomingPaymentPacket.FinalPacket](max = 60 seconds)
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(110000000 msat, paymentHandlerF, nodes("C").paymentInitiator)
    forwardHandlerF.expectMsgType[IncomingPaymentPacket.FinalPacket](max = 60 seconds)
    forwardHandlerF.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(120000000 msat, paymentHandlerC, nodes("F").paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPaymentPacket.FinalPacket](max = 60 seconds)
    forwardHandlerC.forward(buffer.ref)
    sigListener.expectMsgType[ChannelSignatureReceived]
    send(130000000 msat, paymentHandlerC, nodes("F").paymentInitiator)
    forwardHandlerC.expectMsgType[IncomingPaymentPacket.FinalPacket](max = 60 seconds)
    forwardHandlerC.forward(buffer.ref)
    val commitmentsF = sigListener.expectMsgType[ChannelSignatureReceived].commitments
    sigListener.expectNoMessage(1 second)
    assert(commitmentsF.params.commitmentFormat == commitmentFormat)
    // in this commitment, both parties should have a main output, there are four pending htlcs and anchor outputs if applicable
    val localCommitF = commitmentsF.latest.localCommit
    commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => assert(localCommitF.commitTxAndRemoteSig.commitTx.tx.txOut.size == 6)
      case _: Transactions.AnchorOutputsCommitmentFormat => assert(localCommitF.commitTxAndRemoteSig.commitTx.tx.txOut.size == 8)
    }
    val outgoingHtlcExpiry = localCommitF.spec.htlcs.collect { case OutgoingHtlc(add) => add.cltvExpiry }.max
    val htlcTimeoutTxs = localCommitF.htlcTxsAndRemoteSigs.collect { case h@HtlcTxAndRemoteSig(_: Transactions.HtlcTimeoutTx, _) => h }
    val htlcSuccessTxs = localCommitF.htlcTxsAndRemoteSigs.collect { case h@HtlcTxAndRemoteSig(_: Transactions.HtlcSuccessTx, _) => h }
    assert(htlcTimeoutTxs.size == 2)
    assert(htlcSuccessTxs.size == 2)
    // we fulfill htlcs to get the preimages
    buffer.expectMsgType[IncomingPaymentPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage1 = sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPaymentPacket.FinalPacket]
    buffer.forward(paymentHandlerF)
    sigListener.expectMsgType[ChannelSignatureReceived]
    val preimage2 = sender.expectMsgType[PaymentSent].paymentPreimage
    buffer.expectMsgType[IncomingPaymentPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent]
    buffer.expectMsgType[IncomingPaymentPacket.FinalPacket]
    buffer.forward(paymentHandlerC)
    sigListener.expectMsgType[ChannelSignatureReceived]
    sender.expectMsgType[PaymentSent]
    // we then generate blocks to make htlcs timeout (nothing will happen in the channel because all of them have already been fulfilled)
    generateBlocks(outgoingHtlcExpiry.toLong.toInt - getBlockHeight().toInt + 1)
    // we retrieve C's default final address
    sender.send(nodes("C").register, Register.Forward(sender.ref.toTyped[Any], commitmentsF.channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]]
    val finalAddressC = computeBIP84Address(nodes("C").wallet.getP2wpkhPubkey(false), Block.RegtestGenesisBlock.hash)
    // we prepare the revoked transactions F will publish
    val keyManagerF = nodes("F").nodeParams.channelKeyManager
    val channelKeyPathF = keyManagerF.keyPath(commitmentsF.params.localParams, commitmentsF.params.channelConfig)
    val localPerCommitmentPointF = keyManagerF.commitmentPoint(channelKeyPathF, commitmentsF.localCommitIndex)
    val revokedCommitTx = {
      val commitTx = localCommitF.commitTxAndRemoteSig.commitTx
      val localSig = keyManagerF.sign(commitTx, keyManagerF.fundingPublicKey(commitmentsF.params.localParams.fundingKeyPath), TxOwner.Local, commitmentFormat)
      Transactions.addSigs(commitTx, keyManagerF.fundingPublicKey(commitmentsF.params.localParams.fundingKeyPath).publicKey, commitmentsF.params.remoteParams.fundingPubKey, localSig, localCommitF.commitTxAndRemoteSig.remoteSig).tx
    }
    val htlcSuccess = htlcSuccessTxs.zip(Seq(preimage1, preimage2)).map {
      case (htlcTxAndSigs, preimage) =>
        val localSig = keyManagerF.sign(htlcTxAndSigs.htlcTx, keyManagerF.htlcPoint(channelKeyPathF), localPerCommitmentPointF, TxOwner.Local, commitmentFormat)
        Transactions.addSigs(htlcTxAndSigs.htlcTx.asInstanceOf[Transactions.HtlcSuccessTx], localSig, htlcTxAndSigs.remoteSig, preimage, commitmentsF.params.commitmentFormat).tx
    }
    val htlcTimeout = htlcTimeoutTxs.map { htlcTxAndSigs =>
      val localSig = keyManagerF.sign(htlcTxAndSigs.htlcTx, keyManagerF.htlcPoint(channelKeyPathF), localPerCommitmentPointF, TxOwner.Local, commitmentFormat)
      Transactions.addSigs(htlcTxAndSigs.htlcTx.asInstanceOf[Transactions.HtlcTimeoutTx], localSig, htlcTxAndSigs.remoteSig, commitmentsF.params.commitmentFormat).tx
    }
    htlcSuccess.foreach(tx => Transaction.correctlySpends(tx, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    htlcTimeout.foreach(tx => Transaction.correctlySpends(tx, Seq(revokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    RevokedCommitFixture(sender, stateListenerC, revokedCommitTx, htlcSuccess, htlcTimeout, finalAddressC)
  }

}

class StandardChannelIntegrationSpec extends ChannelIntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29740, "eclair.api.port" -> 28090).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29741, "eclair.api.port" -> 28091).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29742, "eclair.api.port" -> 28092).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
  }

  test("connect nodes") {
    // A --- C --- F
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])

    connect(nodes("A"), nodes("C"), 11000000 sat, 0 msat)
    // confirm the funding tx
    generateBlocks(2)
    within(60 seconds) {
      var count = 0
      while (count < 2) {
        if (eventListener.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == NORMAL) count = count + 1
      }
    }

    // generate more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(4)
    awaitAnnouncements(1)
  }

  test("open a wumbo channel C <-> F, wait for longer than the default min_depth, then close") {
    // we open a 5BTC channel and check that we scale `min_depth` up to 13 confirmations
    val funder = nodes("C")
    val fundee = nodes("F")
    val tempChannelId = connect(funder, fundee, 5 btc, 100000000000L msat).channelId

    val sender = TestProbe()
    // mine the funding tx
    generateBlocks(2)
    // get the channelId
    sender.send(fundee.register, Symbol("channels"))
    val Some((_, fundeeChannel)) = sender.expectMsgType[Map[ByteVector32, ActorRef]].find(_._1 == tempChannelId)

    sender.send(fundeeChannel, CMD_GET_CHANNEL_DATA(ActorRef.noSender))
    val channelId = sender.expectMsgType[RES_GET_CHANNEL_DATA[PersistentChannelData]].data.channelId
    awaitCond({
      funder.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      sender.expectMsgType[RES_GET_CHANNEL_STATE].state == WAIT_FOR_CHANNEL_READY
    })

    generateBlocks(6)

    // after 8 blocks the fundee is still waiting for more confirmations
    fundee.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
    assert(sender.expectMsgType[RES_GET_CHANNEL_STATE].state == WAIT_FOR_FUNDING_CONFIRMED)

    // after 8 blocks the funder is still waiting for funding_locked from the fundee
    funder.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
    assert(sender.expectMsgType[RES_GET_CHANNEL_STATE].state == WAIT_FOR_CHANNEL_READY)

    // simulate a disconnection
    sender.send(funder.switchboard, Peer.Disconnect(fundee.nodeParams.nodeId))
    assert(sender.expectMsgType[String] == "disconnecting")

    awaitCond({
      fundee.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val fundeeState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      funder.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val funderState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      fundeeState == OFFLINE && funderState == OFFLINE
    })

    // reconnect and check the fundee is waiting for more conf, funder is waiting for fundee to send channel_ready
    awaitCond({
      // reconnection
      sender.send(fundee.switchboard, Peer.Connect(
        nodeId = funder.nodeParams.nodeId,
        address_opt = funder.nodeParams.publicAddresses.headOption,
        sender.ref,
        isPersistent = true
      ))
      sender.expectMsgType[PeerConnection.ConnectionResult.HasConnection](30 seconds)

      fundee.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val fundeeState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      funder.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val funderState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      fundeeState == WAIT_FOR_FUNDING_CONFIRMED && funderState == WAIT_FOR_CHANNEL_READY
    }, max = 30 seconds, interval = 10 seconds)

    // 5 extra blocks make it 13, just the amount of confirmations needed
    generateBlocks(5)

    awaitCond({
      fundee.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val fundeeState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      funder.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_STATE(ActorRef.noSender))
      val funderState = sender.expectMsgType[RES_GET_CHANNEL_STATE].state
      fundeeState == NORMAL && funderState == NORMAL
    })

    awaitAnnouncements(2)

    val stateListener = TestProbe()
    funder.system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])

    // close that wumbo channel
    sender.send(funder.register, Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val commitmentsC = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data.commitments
    val fundingOutpoint = commitmentsC.latest.commitInput.outPoint
    val finalPubKeyScriptC = Script.write(Script.pay2wpkh(nodes("C").wallet.getP2wpkhPubkey(false)))
    val finalPubKeyScriptF = Script.write(Script.pay2wpkh(nodes("F").wallet.getP2wpkhPubkey(false)))

    fundee.register ! Register.Forward(sender.ref.toTyped[Any], channelId, CMD_CLOSE(sender.ref, None, None))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    // we then wait for C and F to negotiate the closing fee
    awaitCond(stateListener.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)
    // and close the channel
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    awaitCond({
      bitcoinClient.getMempool().pipeTo(sender.ref)
      sender.expectMsgType[Seq[Transaction]].exists(_.txIn.head.outPoint.txid == fundingOutpoint.txid)
    }, max = 20 seconds, interval = 1 second)
    generateBlocks(3)
    awaitCond(stateListener.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)

    bitcoinClient.lookForSpendingTx(None, fundingOutpoint.txid, fundingOutpoint.index.toInt).pipeTo(sender.ref)
    val closingTx = sender.expectMsgType[Transaction]
    assert(closingTx.txOut.map(_.publicKeyScript).toSet == Set(finalPubKeyScriptC, finalPubKeyScriptF))

    awaitAnnouncements(1)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    testDownstreamFulfillLocalCommit(Transactions.DefaultCommitmentFormat)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    testDownstreamFulfillRemoteCommit(Transactions.DefaultCommitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    testDownstreamTimeoutLocalCommit(Transactions.DefaultCommitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    testDownstreamTimeoutRemoteCommit(Transactions.DefaultCommitmentFormat)
  }

  test("punish a node that has published a revoked commit tx") {
    val revokedCommitFixture = testRevokedCommit(Transactions.DefaultCommitmentFormat)
    import revokedCommitFixture._

    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // F publishes the revoked commitment, one HTLC-success, one HTLC-timeout and leaves the other HTLC outputs unclaimed
    bitcoinClient.publishTransaction(revokedCommitTx).pipeTo(sender.ref)
    sender.expectMsg(revokedCommitTx.txid)
    bitcoinClient.publishTransaction(htlcSuccess.head).pipeTo(sender.ref)
    sender.expectMsg(htlcSuccess.head.txid)
    bitcoinClient.publishTransaction(htlcTimeout.head).pipeTo(sender.ref)
    sender.expectMsg(htlcTimeout.head.txid)
    // at this point C should have 6 recv transactions: its previous main output, F's main output and all htlc outputs (taken as punishment)
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      (receivedByC diff previouslyReceivedByC).size == 6
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(2)
    // and we wait for C's channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

}

abstract class AnchorChannelIntegrationSpec extends ChannelIntegrationSpec {

  val commitmentFormat: AnchorOutputsCommitmentFormat

  def connectNodes(expectedChannelType: SupportedChannelType): Unit = {
    // A --- C --- F
    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])
    nodes("C").system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])

    connect(nodes("A"), nodes("C"), 11000000 sat, 0 msat)
    // confirm the funding tx
    generateBlocks(2)
    within(60 seconds) {
      var count = 0
      while (count < 2) {
        val stateEvent = eventListener.expectMsgType[ChannelStateChanged](max = 60 seconds)
        if (stateEvent.currentState == NORMAL) {
          assert(stateEvent.commitments_opt.nonEmpty)
          assert(stateEvent.commitments_opt.get.params.channelType == expectedChannelType)
          count = count + 1
        }
      }
    }

    // generate more blocks so that all funding txs are buried under at least 6 blocks
    generateBlocks(4)
    awaitAnnouncements(1)
  }

  def testOpenPayClose(expectedChannelType: SupportedChannelType): Unit = {
    connect(nodes("C"), nodes("F"), 5000000 sat, 0 msat)
    generateBlocks(6)
    awaitAnnouncements(2)

    // initially all the balance is on C side and F doesn't have an output
    val sender = TestProbe()
    sender.send(nodes("F").register, Symbol("channelsTo"))
    // retrieve the channelId of C <--> F
    val Some(channelId) = sender.expectMsgType[Map[ByteVector32, PublicKey]].find(_._2 == nodes("C").nodeParams.nodeId).map(_._1)

    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val initialStateDataF = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data
    assert(initialStateDataF.commitments.params.channelType == expectedChannelType)
    val initialCommitmentIndex = initialStateDataF.commitments.localCommitIndex

    // the 'to remote' address is a simple script spending to the remote payment basepoint with a 1-block CSV delay
    val toRemoteAddress = Script.pay2wsh(Scripts.toRemoteDelayed(initialStateDataF.commitments.params.remoteParams.paymentBasepoint))

    // toRemote output of C as seen by F
    val Some(toRemoteOutC) = initialStateDataF.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txOut.find(_.publicKeyScript == Script.write(toRemoteAddress))

    // let's make a payment to advance the commit index
    val amountMsat = 4200000.msat
    sender.send(nodes("F").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Invoice]

    // then we make the actual payment
    sender.send(nodes("C").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, maxAttempts = 1, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent](60 seconds)
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)

    // we make sure the htlc has been removed from F's commitment before we force-close
    awaitCond({
      sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
      val stateDataF = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data
      stateDataF.commitments.latest.localCommit.spec.htlcs.isEmpty
    }, max = 20 seconds, interval = 1 second)

    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], channelId, CMD_GET_CHANNEL_DATA(ActorRef.noSender)))
    val stateDataF = sender.expectMsgType[RES_GET_CHANNEL_DATA[DATA_NORMAL]].data
    val commitmentIndex = stateDataF.commitments.localCommitIndex
    val commitTx = stateDataF.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val Some(toRemoteOutCNew) = commitTx.txOut.find(_.publicKeyScript == Script.write(toRemoteAddress))

    // there is a new commitment index in the channel state
    assert(commitmentIndex > initialCommitmentIndex)

    // script pubkeys of toRemote output remained the same across commitments
    assert(toRemoteOutCNew.publicKeyScript == toRemoteOutC.publicKeyScript)
    assert(toRemoteOutCNew.amount < toRemoteOutC.amount)

    val stateListener = TestProbe()
    nodes("C").system.eventStream.subscribe(stateListener.ref, classOf[ChannelStateChanged])

    // we kill the connection between C and F to ensure the close can only be detected on-chain
    disconnectCF(channelId, sender)
    // now let's force close the channel and check the toRemote is what we had at the beginning
    sender.send(nodes("F").register, Register.Forward(sender.ref.toTyped[Any], channelId, CMD_FORCECLOSE(sender.ref)))
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    // we then wait for C to detect the unilateral close and go to CLOSING state
    awaitCond(stateListener.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSING, max = 60 seconds)

    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    awaitCond({
      bitcoinClient.getTransaction(commitTx.txid).map(tx => Some(tx)).recover(_ => None).pipeTo(sender.ref)
      val tx = sender.expectMsgType[Option[Transaction]]
      // the unilateral close contains the static toRemote output
      tx.exists(_.txOut.exists(_.publicKeyScript == toRemoteOutC.publicKeyScript))
    }, max = 20 seconds, interval = 1 second)

    // bury the unilateral close in a block, C should claim its main output
    generateBlocks(2)
    val mainOutputC = OutPoint(commitTx, commitTx.txOut.indexWhere(_.publicKeyScript == toRemoteOutC.publicKeyScript))
    awaitCond({
      bitcoinClient.getMempool().pipeTo(sender.ref)
      sender.expectMsgType[Seq[Transaction]].exists(_.txIn.head.outPoint == mainOutputC)
    }, max = 20 seconds, interval = 1 second)

    // get the claim-remote-output confirmed, then the channel can go to the CLOSED state
    generateBlocks(2)
    awaitCond(stateListener.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

  def testPunishRevokedCommit(): Unit = {
    val revokedCommitFixture = testRevokedCommit(commitmentFormat)
    import revokedCommitFixture._

    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val previouslyReceivedByC = listReceivedByAddress(finalAddressC, sender)
    // F publishes the revoked commitment: it can't publish the HTLC txs because of the CSV 1
    bitcoinClient.publishTransaction(revokedCommitTx).pipeTo(sender.ref)
    sender.expectMsg(revokedCommitTx.txid)
    // get the revoked commitment confirmed: now HTLC txs can be published
    generateBlocks(2)
    // NB: The test cannot be deterministic because there is a race between C and F here; C may publish more quickly and
    // claim the HTLC outputs directly from the commit tx. As a result we may have different combinations of transactions
    // if the test is run several times. It's okay, we just need to make sure that the test never fails.
    bitcoinClient.publishTransaction(htlcSuccess.head)
    bitcoinClient.publishTransaction(htlcTimeout.head)
    // at this point C should have 6 recv transactions: its previous main output, F's main output and all htlc outputs (taken as punishment)
    awaitCond({
      val receivedByC = listReceivedByAddress(finalAddressC, sender)
      (receivedByC diff previouslyReceivedByC).size == 6
    }, max = 30 seconds, interval = 1 second)
    // we generate blocks to make txs confirm
    generateBlocks(2)
    // and we wait for C's channel to close
    awaitCond(stateListenerC.expectMsgType[ChannelStateChanged](max = 60 seconds).currentState == CLOSED, max = 60 seconds)
    awaitAnnouncements(1)
  }

}

class AnchorOutputChannelIntegrationSpec extends AnchorChannelIntegrationSpec {

  override val commitmentFormat = Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29750, "eclair.api.port" -> 28093).asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29751, "eclair.api.port" -> 28094).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29753, "eclair.api.port" -> 28095).asJava).withFallback(withAnchorOutputs).withFallback(commonConfig))
  }

  test("connect nodes") {
    connectNodes(ChannelTypes.StaticRemoteKey())
  }

  test("open channel C <-> F, send payments and close (anchor outputs)") {
    testOpenPayClose(ChannelTypes.AnchorOutputs())
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit, anchor outputs)") {
    testDownstreamFulfillLocalCommit(commitmentFormat)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit, anchor outputs)") {
    testDownstreamFulfillRemoteCommit(commitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit, anchor outputs)") {
    testDownstreamTimeoutLocalCommit(commitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit, anchor outputs)") {
    testDownstreamTimeoutRemoteCommit(commitmentFormat)
  }

  test("punish a node that has published a revoked commit tx (anchor outputs)") {
    testPunishRevokedCommit()
  }

}

class AnchorOutputZeroFeeHtlcTxsChannelIntegrationSpec extends AnchorChannelIntegrationSpec {

  override val commitmentFormat = Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29760, "eclair.api.port" -> 28096).asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29761, "eclair.api.port" -> 28097).asJava).withFallback(withAnchorOutputsZeroFeeHtlcTxs).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 40, "eclair.channel.fulfill-safety-before-timeout-blocks" -> 12, "eclair.server.port" -> 29763, "eclair.api.port" -> 28098).asJava).withFallback(withAnchorOutputsZeroFeeHtlcTxs).withFallback(commonConfig))
  }

  test("connect nodes") {
    connectNodes(ChannelTypes.StaticRemoteKey())
  }

  test("open channel C <-> F, send payments and close (anchor outputs zero fee htlc txs)") {
    testOpenPayClose(ChannelTypes.AnchorOutputsZeroFeeHtlcTx())
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit, anchor outputs zero fee htlc txs)") {
    testDownstreamFulfillLocalCommit(commitmentFormat)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit, anchor outputs zero fee htlc txs)") {
    testDownstreamFulfillRemoteCommit(commitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit, anchor outputs zero fee htlc txs)") {
    testDownstreamTimeoutLocalCommit(commitmentFormat)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit, anchor outputs zero fee htlc txs)") {
    testDownstreamTimeoutRemoteCommit(commitmentFormat)
  }

  test("punish a node that has published a revoked commit tx (anchor outputs)") {
    testPunishRevokedCommit()
  }

}

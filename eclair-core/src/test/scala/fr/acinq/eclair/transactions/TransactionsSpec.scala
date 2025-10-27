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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.scalacompat.Crypto.TaprootTweak.KeyPathTweak
import fr.acinq.bitcoin.scalacompat.Crypto._
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, ByteVector64, Crypto, MilliBtc, MilliBtcDouble, Musig2, OP_2, OP_CHECKMULTISIG, OP_PUSHDATA, OP_RETURN, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.{ScriptFlags, SigVersion}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelSpendSignature
import fr.acinq.eclair.crypto.keymanager.{LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions.AnchorOutputsCommitmentFormat.anchorAmount
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import grizzled.slf4j.Logging
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.util.Random

/**
 * Created by PM on 16/12/2016.
 */

class TransactionsSpec extends AnyFunSuite with Logging {
  private val localFundingPriv = randomKey()
  private val remoteFundingPriv = randomKey()
  private val localRevocationPriv = randomKey()
  private val localPaymentPriv = randomKey()
  private val localPaymentBasePoint = randomKey().publicKey
  private val localDelayedPaymentPriv = randomKey()
  private val remotePaymentPriv = randomKey()
  private val localHtlcPriv = randomKey()
  private val remoteHtlcPriv = randomKey()
  // Keys used by the local node to spend outputs of its local commitment.
  private val localKeys = LocalCommitmentKeys(
    ourDelayedPaymentKey = localDelayedPaymentPriv,
    theirPaymentPublicKey = remotePaymentPriv.publicKey,
    ourPaymentBasePoint = localPaymentBasePoint,
    ourHtlcKey = localHtlcPriv,
    theirHtlcPublicKey = remoteHtlcPriv.publicKey,
    revocationPublicKey = localRevocationPriv.publicKey,
  )
  // Keys used by the remote node to spend outputs of our local commitment.
  private val remoteKeys = RemoteCommitmentKeys(
    ourPaymentKey = remotePaymentPriv,
    theirDelayedPaymentPublicKey = localDelayedPaymentPriv.publicKey,
    ourPaymentBasePoint = remotePaymentPriv.publicKey,
    ourHtlcKey = remoteHtlcPriv,
    theirHtlcPublicKey = localHtlcPriv.publicKey,
    revocationPublicKey = localRevocationPriv.publicKey,
  )
  private val toLocalDelay = CltvExpiryDelta(144)
  private val localDustLimit = Satoshi(546)
  private val feeratePerKw = FeeratePerKw(22000 sat)

  test("extract csv and cltv timeouts") {
    val parentTxId1 = randomTxId()
    val parentTxId2 = randomTxId()
    val parentTxId3 = randomTxId()
    val txIn = Seq(
      TxIn(OutPoint(parentTxId1, 3), Nil, 3),
      TxIn(OutPoint(parentTxId2, 1), Nil, 4),
      TxIn(OutPoint(parentTxId3, 0), Nil, 5),
      TxIn(OutPoint(randomTxId(), 4), Nil, 0),
      TxIn(OutPoint(parentTxId1, 2), Nil, 5),
    )
    val tx = Transaction(2, txIn, Nil, 10)
    val expected = Map(
      parentTxId1 -> 5,
      parentTxId2 -> 4,
      parentTxId3 -> 5,
    )
    assert(expected == Scripts.csvTimeouts(tx))
    assert(BlockHeight(10) == Scripts.cltvTimeout(tx))
  }

  test("encode/decode sequence and lockTime (one example)") {
    val txnumber = 0x11F71FB268DL

    val (sequence, locktime) = encodeTxNumber(txnumber)
    assert(sequence == 0x80011F71L)
    assert(locktime == 0x20FB268DL)

    val txnumber1 = decodeTxNumber(sequence, locktime)
    assert(txnumber == txnumber1)
  }

  test("reconstruct txNumber from sequence and lockTime") {
    for (_ <- 0 until 1000) {
      val txnumber = Random.nextLong() & 0xffffffffffffL
      val (sequence, locktime) = encodeTxNumber(txnumber)
      val txnumber1 = decodeTxNumber(sequence, locktime)
      assert(txnumber == txnumber1)
    }
  }

  test("compute fees") {
    val htlcs = Set[DirectedHtlc](
      OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 5000000 msat, ByteVector32.Zeroes, CltvExpiry(552), TestConstants.emptyOnionPacket, None, accountable = false, None)),
      OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000 msat, ByteVector32.Zeroes, CltvExpiry(553), TestConstants.emptyOnionPacket, None, accountable = false, None)),
      IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 7000000 msat, ByteVector32.Zeroes, CltvExpiry(550), TestConstants.emptyOnionPacket, None, accountable = false, None)),
      IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 800000 msat, ByteVector32.Zeroes, CltvExpiry(551), TestConstants.emptyOnionPacket, None, accountable = false, None))
    )
    val spec = CommitmentSpec(htlcs, FeeratePerKw(5000 sat), toLocal = 0 msat, toRemote = 0 msat)
    val fee = commitTxFeeMsat(546 sat, spec, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    assert(fee == 9_060_000.msat)
  }

  test("pre-compute wallet input and output weight") {
    // ECDSA signatures are usually between at 71 and 73 bytes.
    val dummyEcdsaSig = ByteVector.fill(73)(0)
    val dummySchnorrSig = ByteVector64.Zeroes
    val dummyTx = Transaction(
      version = 2,
      txIn = Seq(
        TxIn(OutPoint(randomTxId(), 5), ByteVector.empty, 0, Script.witnessPay2wpkh(randomKey().publicKey, dummyEcdsaSig)),
        TxIn(OutPoint(randomTxId(), 3), ByteVector.empty, 0, Script.witnessKeyPathPay2tr(dummySchnorrSig))
      ),
      txOut = Seq(
        TxOut(250_000 sat, Script.pay2wpkh(randomKey().publicKey)),
        TxOut(250_000 sat, Script.pay2tr(randomKey().publicKey.xOnly, KeyPathTweak)),
      ),
      lockTime = 0
    )
    assert(dummyTx.weight() - dummyTx.copy(txIn = dummyTx.txIn.tail).weight() == p2wpkhInputWeight)
    assert(dummyTx.weight() - dummyTx.copy(txIn = dummyTx.txIn.take(1)).weight() == p2trInputWeight)
    assert(dummyTx.weight() - dummyTx.copy(txOut = dummyTx.txOut.tail).weight() == p2wpkhOutputWeight)
    assert(dummyTx.weight() - dummyTx.copy(txOut = dummyTx.txOut.take(1)).weight() == p2trOutputWeight)
  }

  private def checkExpectedWeight(actual: Int, expected: Int, commitmentFormat: CommitmentFormat): Unit = {
    commitmentFormat match {
      case _: SimpleTaprootChannelCommitmentFormat => assert(actual == expected)
      case _: SegwitV0CommitmentFormat =>
        // ECDSA signatures are der-encoded, which creates some variability in signature size compared to the baseline.
        assert(actual <= expected + 4)
        assert(actual >= expected - 4)
    }
  }

  test("generate valid commitment with some outputs that don't materialize (anchor outputs)") {
    val commitInput = InputInfo(OutPoint(randomTxId(), 0), TxOut(700.millibtc.toSatoshi, Script.pay2wpkh(randomKey().publicKey)))
    val spec = CommitmentSpec(htlcs = Set.empty, commitTxFeerate = feeratePerKw, toLocal = 400.millibtc.toMilliSatoshi, toRemote = 300.millibtc.toMilliSatoshi)
    val commitFeeAndAnchorCost = commitTxTotalCost(localDustLimit, spec, UnsafeLegacyAnchorOutputsCommitmentFormat)
    val belowDust = (localDustLimit * 0.9).toMilliSatoshi
    val belowDustWithFeeAndAnchors = (localDustLimit + commitFeeAndAnchorCost * 0.9).toMilliSatoshi

    {
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, spec, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.size == 4)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocalAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemoteAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocal]).get.txOut.amount.toMilliSatoshi == spec.toLocal - commitFeeAndAnchorCost)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemote]).get.txOut.amount.toMilliSatoshi == spec.toRemote)
    }
    {
      val toRemoteFundeeBelowDust = spec.copy(toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, toRemoteFundeeBelowDust, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.size == 2)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocalAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocal]).get.txOut.amount.toMilliSatoshi == spec.toLocal - commitFeeAndAnchorCost)
    }
    {
      val toLocalFunderBelowDust = spec.copy(toLocal = belowDustWithFeeAndAnchors)
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, toLocalFunderBelowDust, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.size == 2)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemoteAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemote]).get.txOut.amount.toMilliSatoshi == spec.toRemote)
    }
    {
      val toRemoteFunderBelowDust = spec.copy(toRemote = belowDustWithFeeAndAnchors)
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = false, localDustLimit, toLocalDelay, toRemoteFunderBelowDust, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.size == 2)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocalAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToLocal]).get.txOut.amount.toMilliSatoshi == spec.toLocal)
    }
    {
      val toLocalFundeeBelowDust = spec.copy(toLocal = belowDust)
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = false, localDustLimit, toLocalDelay, toLocalFundeeBelowDust, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.size == 2)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemoteAnchor]).get.txOut.amount == anchorAmount)
      assert(outputs.find(_.isInstanceOf[CommitmentOutput.ToRemote]).get.txOut.amount.toMilliSatoshi == spec.toRemote - commitFeeAndAnchorCost)
    }
    {
      val allBelowDust = spec.copy(toLocal = belowDust, toRemote = belowDust)
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, allBelowDust, UnsafeLegacyAnchorOutputsCommitmentFormat)
      assert(outputs.isEmpty)
    }
  }

  private def testCommitAndHtlcTxs(commitmentFormat: CommitmentFormat): Unit = {
    val walletPriv = randomKey()
    val walletPub = walletPriv.publicKey
    val finalPubKeyScript = Script.write(Script.pay2wpkh(walletPub))
    val fundingInfo = makeFundingScript(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitmentFormat)
    val fundingTx = Transaction(version = 2, txIn = Nil, txOut = TxOut(Btc(1), fundingInfo.pubkeyScript) :: Nil, lockTime = 0)
    val fundingTxOutpoint = OutPoint(fundingTx.txid, 0)
    val commitInput = makeFundingInputInfo(fundingTxOutpoint.txid, fundingTxOutpoint.index.toInt, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitmentFormat)

    val paymentPreimages = Seq(randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
    val paymentPreimageMap = paymentPreimages.map(p => sha256(p) -> p).toMap

    // htlc1, htlc2a and htlc2b are regular IN/OUT htlcs
    val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, MilliBtc(100).toMilliSatoshi, sha256(paymentPreimages(0)), CltvExpiry(300), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val htlc2a = UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliBtc(50).toMilliSatoshi, sha256(paymentPreimages(1)), CltvExpiry(310), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val htlc2b = UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliBtc(150).toMilliSatoshi, sha256(paymentPreimages(1)), CltvExpiry(310), TestConstants.emptyOnionPacket, None, accountable = false, None)
    // htlc3 and htlc4 are dust IN/OUT htlcs, with an amount large enough to be included in the commit tx, but too small to be claimed at 2nd stage
    val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 3, (localDustLimit + weight2fee(feeratePerKw, commitmentFormat.htlcTimeoutWeight)).toMilliSatoshi, sha256(paymentPreimages(2)), CltvExpiry(295), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val htlc4 = UpdateAddHtlc(ByteVector32.Zeroes, 4, (localDustLimit + weight2fee(feeratePerKw, commitmentFormat.htlcSuccessWeight)).toMilliSatoshi, sha256(paymentPreimages(3)), CltvExpiry(300), TestConstants.emptyOnionPacket, None, accountable = false, None)
    // htlc5 and htlc6 are dust IN/OUT htlcs
    val htlc5 = UpdateAddHtlc(ByteVector32.Zeroes, 5, (localDustLimit * 0.9).toMilliSatoshi, sha256(paymentPreimages(4)), CltvExpiry(295), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val htlc6 = UpdateAddHtlc(ByteVector32.Zeroes, 6, (localDustLimit * 0.9).toMilliSatoshi, sha256(paymentPreimages(5)), CltvExpiry(305), TestConstants.emptyOnionPacket, None, accountable = false, None)
    // htlc7 and htlc8 are at the dust limit when we ignore 2nd-stage tx fees
    val htlc7 = UpdateAddHtlc(ByteVector32.Zeroes, 7, localDustLimit.toMilliSatoshi, sha256(paymentPreimages(6)), CltvExpiry(300), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val htlc8 = UpdateAddHtlc(ByteVector32.Zeroes, 8, localDustLimit.toMilliSatoshi, sha256(paymentPreimages(7)), CltvExpiry(302), TestConstants.emptyOnionPacket, None, accountable = false, None)
    val spec = CommitmentSpec(
      htlcs = Set(
        OutgoingHtlc(htlc1),
        IncomingHtlc(htlc2a),
        IncomingHtlc(htlc2b),
        OutgoingHtlc(htlc3),
        IncomingHtlc(htlc4),
        OutgoingHtlc(htlc5),
        IncomingHtlc(htlc6),
        OutgoingHtlc(htlc7),
        IncomingHtlc(htlc8),
      ),
      commitTxFeerate = feeratePerKw,
      toLocal = 400.millibtc.toMilliSatoshi,
      toRemote = 300.millibtc.toMilliSatoshi)
    val localNonce = Musig2.generateNonce(randomBytes32(), Left(localFundingPriv), Seq(localFundingPriv.publicKey), None, None)
    val remoteNonce = Musig2.generateNonce(randomBytes32(), Left(remoteFundingPriv), Seq(remoteFundingPriv.publicKey), None, None)
    val publicNonces = Seq(localNonce, remoteNonce).map(_.publicNonce)

    val (commitTx, commitTxOutputs, htlcTimeoutTxs, htlcSuccessTxs) = {
      val commitTxNumber = 0x404142434445L
      val outputs = makeCommitTxOutputs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, commitInput, localKeys.publicKeys, payCommitTxFees = true, localDustLimit, toLocalDelay, spec, commitmentFormat)
      val txInfo = makeCommitTx(commitInput, commitTxNumber, localPaymentPriv.publicKey, remotePaymentPriv.publicKey, localIsChannelOpener = true, commitmentFormat, outputs)
      val commitTx = commitmentFormat match {
        case _: SimpleTaprootChannelCommitmentFormat =>
          val Right(commitTx) = for {
            localPartialSig <- txInfo.partialSign(localFundingPriv, remoteFundingPriv.publicKey, localNonce, publicNonces)
            remotePartialSig <- txInfo.partialSign(remoteFundingPriv, localFundingPriv.publicKey, remoteNonce, publicNonces)
            _ = assert(txInfo.checkRemotePartialSignature(localFundingPriv.publicKey, remoteFundingPriv.publicKey, remotePartialSig, localNonce.publicNonce))
            invalidRemotePartialSig = ChannelSpendSignature.PartialSignatureWithNonce(randomBytes32(), remotePartialSig.nonce)
            _ = assert(!txInfo.checkRemotePartialSignature(localFundingPriv.publicKey, remoteFundingPriv.publicKey, invalidRemotePartialSig, localNonce.publicNonce))
            tx <- txInfo.aggregateSigs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, localPartialSig, remotePartialSig)
          } yield tx
          commitTx
        case _: SegwitV0CommitmentFormat =>
          val localSig = txInfo.sign(localFundingPriv, remoteFundingPriv.publicKey)
          val remoteSig = txInfo.sign(remoteFundingPriv, localFundingPriv.publicKey)
          assert(txInfo.checkRemoteSig(localFundingPubkey = localFundingPriv.publicKey, remoteFundingPriv.publicKey, remoteSig))
          val invalidRemoteSig = ChannelSpendSignature.IndividualSignature(randomBytes64())
          assert(!txInfo.checkRemoteSig(localFundingPubkey = localFundingPriv.publicKey, remoteFundingPriv.publicKey, invalidRemoteSig))
          val commitTx = txInfo.aggregateSigs(localFundingPriv.publicKey, remoteFundingPriv.publicKey, localSig, remoteSig)
          commitTx
      }
      commitTx.correctlySpends(Seq(fundingTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // We check the expected weight of the commit input:
      val commitInputWeight = commitTx.copy(txIn = Seq(commitTx.txIn.head, commitTx.txIn.head)).weight() - commitTx.weight()
      checkExpectedWeight(commitInputWeight, commitmentFormat.fundingInputWeight, commitmentFormat)
      val htlcTxs = makeHtlcTxs(commitTx, outputs, commitmentFormat)
      val expiries = htlcTxs.map(tx => tx.htlcId -> tx.htlcExpiry.toLong).toMap
      val htlcSuccessTxs = htlcTxs.collect { case tx: UnsignedHtlcSuccessTx => tx }
      val htlcTimeoutTxs = htlcTxs.collect { case tx: UnsignedHtlcTimeoutTx => tx }
      commitmentFormat match {
        case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat | ZeroFeeCommitmentFormat =>
          assert(htlcTxs.length == 7)
          assert(expiries == Map(0 -> 300, 1 -> 310, 2 -> 310, 3 -> 295, 4 -> 300, 7 -> 300, 8 -> 302))
          assert(htlcTimeoutTxs.size == 3) // htlc1 and htlc3 and htlc7
          assert(htlcTimeoutTxs.map(_.htlcId).toSet == Set(0, 3, 7))
          assert(htlcSuccessTxs.size == 4) // htlc2a, htlc2b, htlc4 and htlc8
          assert(htlcSuccessTxs.map(_.htlcId).toSet == Set(1, 2, 4, 8))
        case UnsafeLegacyAnchorOutputsCommitmentFormat | PhoenixSimpleTaprootChannelCommitmentFormat =>
          assert(htlcTxs.length == 5)
          assert(expiries == Map(0 -> 300, 1 -> 310, 2 -> 310, 3 -> 295, 4 -> 300))
          assert(htlcTimeoutTxs.size == 2) // htlc1 and htlc3
          assert(htlcTimeoutTxs.map(_.htlcId).toSet == Set(0, 3))
          assert(htlcSuccessTxs.size == 3) // htlc2a, htlc2b and htlc4
          assert(htlcSuccessTxs.map(_.htlcId).toSet == Set(1, 2, 4))
      }
      (commitTx, outputs, htlcTimeoutTxs, htlcSuccessTxs)
    }
    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        assert(commitTx.version == 2)
        htlcTimeoutTxs.foreach(htlcTx => assert(htlcTx.tx.version == 2))
        htlcSuccessTxs.foreach(htlcTx => assert(htlcTx.tx.version == 2))
      case ZeroFeeCommitmentFormat =>
        assert(commitTx.version == 3)
        htlcTimeoutTxs.foreach(htlcTx => assert(htlcTx.tx.version == 3))
        htlcSuccessTxs.foreach(htlcTx => assert(htlcTx.tx.version == 3))
    }

    {
      // local spends main delayed output
      val Right(claimMainOutputTx) = ClaimLocalDelayedOutputTx.createUnsignedTx(localKeys, commitTx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat).map(_.sign())
      checkExpectedWeight(claimMainOutputTx.weight(), commitmentFormat.toLocalDelayedWeight, commitmentFormat)
      Transaction.correctlySpends(claimMainOutputTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // remote spends main delayed output
      val Right(claimRemoteDelayedOutputTx) = ClaimRemoteDelayedOutputTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, finalPubKeyScript, feeratePerKw, commitmentFormat).map(_.sign())
      checkExpectedWeight(claimRemoteDelayedOutputTx.weight(), commitmentFormat.toRemoteWeight, commitmentFormat)
      Transaction.correctlySpends(claimRemoteDelayedOutputTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // local spends local anchor with additional wallet inputs
      val walletAmount = 50_000 sat
      val walletInputs = WalletInputs(Seq(
        WalletInput(TxIn(OutPoint(randomTxId(), 3), Nil, 0), TxOut(walletAmount, Script.pay2wpkh(walletPub))),
        WalletInput(TxIn(OutPoint(randomTxId(), 0), Nil, 0), TxOut(walletAmount, Script.pay2wpkh(walletPub))),
      ), changeOutput_opt = Some(TxOut(25_000 sat, Script.pay2wpkh(walletPub))))
      val Right(claimAnchorTx) = ClaimLocalAnchorTx.createUnsignedTx(localFundingPriv, localKeys, commitTx, commitmentFormat).map(anchorTx => {
        val locallySignedTx = anchorTx.sign(walletInputs)
        val sig1 = locallySignedTx.signInput(1, Script.pay2pkh(walletPub), SIGHASH_ALL, walletAmount, SigVersion.SIGVERSION_WITNESS_V0, walletPriv)
        val sig2 = locallySignedTx.signInput(2, Script.pay2pkh(walletPub), SIGHASH_ALL, walletAmount, SigVersion.SIGVERSION_WITNESS_V0, walletPriv)
        val signedTx = locallySignedTx
          .updateWitness(1, Script.witnessPay2wpkh(walletPub, sig1))
          .updateWitness(2, Script.witnessPay2wpkh(walletPub, sig2))
        anchorTx.copy(tx = signedTx)
      })
      val spentOutputs = walletInputs.spentUtxos + (claimAnchorTx.input.outPoint -> claimAnchorTx.input.txOut)
      Transaction.correctlySpends(claimAnchorTx.tx, spentOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      val anchorInputWeight = claimAnchorTx.tx.weight() - claimAnchorTx.tx.copy(txIn = claimAnchorTx.tx.txIn.tail).weight()
      checkExpectedWeight(anchorInputWeight, commitmentFormat.anchorInputWeight, commitmentFormat)
      val anchorTxWeight = claimAnchorTx.tx.copy(txIn = claimAnchorTx.tx.txIn.take(1), txOut = Nil).weight()
      checkExpectedWeight(anchorTxWeight, claimAnchorTx.expectedWeight, commitmentFormat)
    }
    {
      // remote spends remote anchor
      val Right(claimAnchorOutputTx) = ClaimRemoteAnchorTx.createUnsignedTx(remoteFundingPriv, remoteKeys, commitTx, commitmentFormat)
      commitmentFormat match {
        case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => assert(!claimAnchorOutputTx.validate(Map.empty))
        // Standard P2A outputs are valid with an empty witness.
        case ZeroFeeCommitmentFormat => assert(claimAnchorOutputTx.validate(Map.empty))
      }
      val signedTx = claimAnchorOutputTx.sign()
      Transaction.correctlySpends(signedTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // remote spends local main delayed output with revocation key
      val Right(mainPenaltyTx) = MainPenaltyTx.createUnsignedTx(remoteKeys, localRevocationPriv, commitTx, localDustLimit, finalPubKeyScript, toLocalDelay, feeratePerKw, commitmentFormat).map(_.sign())
      checkExpectedWeight(mainPenaltyTx.weight(), commitmentFormat.mainPenaltyWeight, commitmentFormat)
      Transaction.correctlySpends(mainPenaltyTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // local spends received htlc with HTLC-timeout tx
      for (htlcTimeoutTx <- htlcTimeoutTxs) {
        val remoteSig = htlcTimeoutTx.localSig(remoteKeys)
        assert(htlcTimeoutTx.checkRemoteSig(localKeys, remoteSig))
        val signedTx = htlcTimeoutTx.addRemoteSig(localKeys, remoteSig).sign()
        Transaction.correctlySpends(signedTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // local detects when remote doesn't use the right sighash flags
        val invalidSighash = Seq(SIGHASH_ALL, SIGHASH_ALL | SIGHASH_ANYONECANPAY, SIGHASH_SINGLE, SIGHASH_NONE)
        for (sighash <- invalidSighash) {
          val invalidRemoteSig = htlcTimeoutTx.localSigWithInvalidSighash(remoteKeys, sighash)
          assert(!htlcTimeoutTx.checkRemoteSig(localKeys, invalidRemoteSig))
        }
      }
    }
    {
      // local spends delayed output of htlc1 timeout tx
      val Right(htlcDelayed) = HtlcDelayedTx.createUnsignedTx(localKeys, htlcTimeoutTxs(1).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat).map(_.sign())
      checkExpectedWeight(htlcDelayed.weight(), commitmentFormat.htlcDelayedWeight, commitmentFormat)
      Transaction.correctlySpends(htlcDelayed, Seq(htlcTimeoutTxs(1).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // local can't claim delayed output of htlc3 timeout tx because it is below the dust limit
      val htlcDelayed1 = HtlcDelayedTx.createUnsignedTx(localKeys, htlcTimeoutTxs(0).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      assert(htlcDelayed1 == Left(AmountBelowDustLimit))
    }
    {
      // local spends offered htlc with HTLC-success tx
      for (htlcSuccessTx <- htlcSuccessTxs(0) :: htlcSuccessTxs(1) :: htlcSuccessTxs(2) :: Nil) {
        val preimage = paymentPreimageMap(htlcSuccessTx.paymentHash)
        val remoteSig = htlcSuccessTx.localSig(remoteKeys)
        val signedTx = htlcSuccessTx.addRemoteSig(localKeys, remoteSig, preimage).sign()
        Transaction.correctlySpends(signedTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assert(htlcSuccessTx.checkRemoteSig(localKeys, remoteSig))
        // local detects when remote doesn't use the right sighash flags
        val invalidSighash = Seq(SIGHASH_ALL, SIGHASH_ALL | SIGHASH_ANYONECANPAY, SIGHASH_SINGLE, SIGHASH_NONE)
        for (sighash <- invalidSighash) {
          val invalidRemoteSig = htlcSuccessTx.localSigWithInvalidSighash(remoteKeys, sighash)
          assert(!htlcSuccessTx.checkRemoteSig(localKeys, invalidRemoteSig))
        }
      }
    }
    {
      // local spends delayed output of htlc2a and htlc2b success txs
      val Right(htlcDelayedA) = HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs(1).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat).map(_.sign())
      val Right(htlcDelayedB) = HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs(2).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat).map(_.sign())
      Seq(htlcDelayedA, htlcDelayedB).foreach(htlcDelayed => checkExpectedWeight(htlcDelayed.weight(), commitmentFormat.htlcDelayedWeight, commitmentFormat))
      Transaction.correctlySpends(htlcDelayedA, Seq(htlcSuccessTxs(1).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(htlcDelayedB, Seq(htlcSuccessTxs(2).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // local can't claim delayed output of htlc4 success tx because it is below the dust limit
      val htlcDelayedC = HtlcDelayedTx.createUnsignedTx(localKeys, htlcSuccessTxs(0).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      assert(htlcDelayedC == Left(AmountBelowDustLimit))
    }
    {
      // remote spends local->remote htlc outputs directly in case of success
      for (htlc <- htlc1 :: htlc3 :: Nil) {
        val paymentPreimage = paymentPreimageMap(htlc.paymentHash)
        val Right(claimHtlcSuccessTx) = ClaimHtlcSuccessTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, commitTxOutputs, finalPubKeyScript, htlc, paymentPreimage, feeratePerKw, commitmentFormat).map(_.sign())
        checkExpectedWeight(claimHtlcSuccessTx.weight(), commitmentFormat.claimHtlcSuccessWeight, commitmentFormat)
        Transaction.correctlySpends(claimHtlcSuccessTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      }
    }
    {
      // remote spends htlc1's htlc-timeout tx with revocation key
      val Seq(Right(claimHtlcDelayedPenaltyTx)) = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcTimeoutTxs(1).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      val signedTx = claimHtlcDelayedPenaltyTx.sign()
      checkExpectedWeight(signedTx.weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat)
      Transaction.correctlySpends(signedTx, Seq(htlcTimeoutTxs(1).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // remote can't claim revoked output of htlc3's htlc-timeout tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcTimeoutTxs(0).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      assert(claimHtlcDelayedPenaltyTx1 == Seq(Left(AmountBelowDustLimit)))
    }
    {
      // remote spends remote->local htlc output directly in case of timeout
      for (htlc <- Seq(htlc2a, htlc2b)) {
        val Right(claimHtlcTimeoutTx) = ClaimHtlcTimeoutTx.createUnsignedTx(remoteKeys, commitTx, localDustLimit, commitTxOutputs, finalPubKeyScript, htlc, feeratePerKw, commitmentFormat).map(_.sign())
        checkExpectedWeight(claimHtlcTimeoutTx.weight(), commitmentFormat.claimHtlcTimeoutWeight, commitmentFormat)
        Transaction.correctlySpends(claimHtlcTimeoutTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      }
    }
    {
      // remote spends htlc2a/htlc2b's htlc-success tx with revocation key
      val Seq(Right(claimHtlcDelayedPenaltyTxA)) = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs(1).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      val Seq(Right(claimHtlcDelayedPenaltyTxB)) = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs(2).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      Seq(claimHtlcDelayedPenaltyTxA, claimHtlcDelayedPenaltyTxB).foreach(claimHtlcSuccessPenaltyTx => checkExpectedWeight(claimHtlcSuccessPenaltyTx.sign().weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat))
      Transaction.correctlySpends(claimHtlcDelayedPenaltyTxA.sign(), Seq(htlcSuccessTxs(1).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimHtlcDelayedPenaltyTxB.sign(), Seq(htlcSuccessTxs(2).tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // remote can't claim revoked output of htlc4's htlc-success tx because it is below the dust limit
      val claimHtlcDelayedPenaltyTx1 = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, htlcSuccessTxs(0).tx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      assert(claimHtlcDelayedPenaltyTx1 == Seq(Left(AmountBelowDustLimit)))
    }
    {
      // remote spends all htlc txs aggregated in a single tx
      val txIn = htlcTimeoutTxs.flatMap(_.tx.txIn) ++ htlcSuccessTxs.flatMap(_.tx.txIn)
      val txOut = htlcTimeoutTxs.flatMap(_.tx.txOut) ++ htlcSuccessTxs.flatMap(_.tx.txOut)
      val aggregatedHtlcTx = Transaction(2, txIn, txOut, 0)
      val claimHtlcDelayedPenaltyTxs = ClaimHtlcDelayedOutputPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, aggregatedHtlcTx, localDustLimit, toLocalDelay, finalPubKeyScript, feeratePerKw, commitmentFormat)
      val skipped = claimHtlcDelayedPenaltyTxs.collect { case Left(reason) => reason }
      val claimed = claimHtlcDelayedPenaltyTxs.collect { case Right(tx) => tx }
      commitmentFormat match {
        case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat | ZeroFeeCommitmentFormat =>
          assert(claimHtlcDelayedPenaltyTxs.size == 7)
          assert(skipped.size == 2)
          assert(skipped.toSet == Set(AmountBelowDustLimit))
          assert(claimed.size == 5)
          assert(claimed.map(_.input.outPoint).toSet.size == 5)
        case UnsafeLegacyAnchorOutputsCommitmentFormat | PhoenixSimpleTaprootChannelCommitmentFormat =>
          assert(claimHtlcDelayedPenaltyTxs.size == 5)
          assert(skipped.size == 2)
          assert(skipped.toSet == Set(AmountBelowDustLimit))
          assert(claimed.size == 3)
          assert(claimed.map(_.input.outPoint).toSet.size == 3)
      }
      claimed.foreach { htlcPenaltyTx =>
        val signedTx = htlcPenaltyTx.sign()
        checkExpectedWeight(signedTx.weight(), commitmentFormat.claimHtlcPenaltyWeight, commitmentFormat)
        Transaction.correctlySpends(signedTx, Seq(aggregatedHtlcTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      }
    }
    {
      // remote spends htlc outputs with revocation key
      val htlcs = spec.htlcs.map(_.add).map(add => (add.paymentHash, add.cltvExpiry)).toSeq
      val htlcPenaltyTxs = HtlcPenaltyTx.createUnsignedTxs(remoteKeys, localRevocationPriv, commitTx, htlcs, localDustLimit, finalPubKeyScript, feeratePerKw, commitmentFormat)
      assert(htlcPenaltyTxs.collect { case Right(htlcPenaltyTx) => htlcPenaltyTx.paymentHash }.toSet == Set(htlc1, htlc2a, htlc2b, htlc3, htlc4).map(_.paymentHash)) // the first 5 htlcs are above the dust limit
      htlcPenaltyTxs.collect { case Right(htlcPenaltyTx) => htlcPenaltyTx }.foreach { htlcPenaltyTx =>
        val signedTx = htlcPenaltyTx.sign()
        val expectedWeight = if (htlcTimeoutTxs.map(_.input.outPoint).toSet.contains(htlcPenaltyTx.input.outPoint)) {
          commitmentFormat.htlcOfferedPenaltyWeight
        } else {
          commitmentFormat.htlcReceivedPenaltyWeight
        }
        checkExpectedWeight(signedTx.weight(), expectedWeight, commitmentFormat)
        Transaction.correctlySpends(signedTx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      }
    }
  }

  test("generate valid commitment and htlc transactions (legacy anchor outputs)") {
    testCommitAndHtlcTxs(UnsafeLegacyAnchorOutputsCommitmentFormat)
  }

  test("generate valid commitment and htlc transactions (zero fee anchor outputs)") {
    testCommitAndHtlcTxs(ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("generate valid commitment and htlc transactions (simple taproot channels)") {
    testCommitAndHtlcTxs(ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("generate valid commitment and htlc transactions (phoenix simple taproot channels)") {
    testCommitAndHtlcTxs(PhoenixSimpleTaprootChannelCommitmentFormat)
  }

  test("generate valid commitment and htlc transactions (zero-fee commitments)") {
    testCommitAndHtlcTxs(ZeroFeeCommitmentFormat)
  }

  test("generate taproot NUMS point") {
    val bin = 2.toByte +: Crypto.sha256(ByteVector.fromValidHex("0000000000000002") ++ ByteVector.view("Lightning Simple Taproot".getBytes))
    val pub = PublicKey(bin)
    assert(pub == Taproot.NUMS_POINT)
  }

  test("sort public keys using lexicographic ordering") {
    val pubkey1 = PublicKey(hex"0277174bdb8e0003a03334f0f5d0be2b9f4c0812ee4097b0c23d29f505b8e9d9f8")
    val pubkey2 = PublicKey(hex"03e27a9ca7c8d6348868f8b4a3974e9eb91f7df7d6532f9b0a50f0314cb28c8d31")
    assert(Seq(pubkey1, pubkey2) == Scripts.sort(Seq(pubkey1, pubkey2)))
    assert(Seq(pubkey1, pubkey2) == Scripts.sort(Seq(pubkey2, pubkey1)))
    assert(multiSig2of2(pubkey1, pubkey2) == multiSig2of2(pubkey2, pubkey1))
    assert(multiSig2of2(pubkey2, pubkey1) == Seq(OP_2, OP_PUSHDATA(pubkey1.value), OP_PUSHDATA(pubkey2.value), OP_2, OP_CHECKMULTISIG))
    assert(Taproot.musig2Aggregate(pubkey1, pubkey2) == Taproot.musig2Aggregate(pubkey2, pubkey1))
    assert(Taproot.musig2Aggregate(pubkey2, pubkey1) == Musig2.aggregateKeys(Seq(pubkey1, pubkey2)))
  }

  test("find our output in closing tx") {
    val commitInput = makeFundingInputInfo(randomTxId(), 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    val localPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32()).publicKey))
    val remotePubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32()).publicKey))

    {
      // Different amounts, both outputs untrimmed, local is funder:
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 250_000_000 msat)
      val closingTx = ClosingTx.createUnsignedTx(commitInput, localPubKeyScript, remotePubKeyScript, localPaysClosingFees = true, localDustLimit, 1000 sat, spec)
      assert(closingTx.tx.txOut.length == 2)
      assert(closingTx.toLocalOutput_opt.nonEmpty)
      val toLocal = closingTx.toLocalOutput_opt.get
      assert(toLocal.publicKeyScript == localPubKeyScript)
      assert(toLocal.amount == 149_000.sat) // funder pays the fee
      val toRemoteIndex = (closingTx.toLocalOutputIndex_opt.get + 1) % 2
      assert(closingTx.tx.txOut(toRemoteIndex.toInt).amount == 250_000.sat)
    }
    {
      // Different amounts, both outputs untrimmed, local is closer (option_simple_close):
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 250_000_000 msat)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByUs(5_000 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.localAndRemote_opt.nonEmpty)
      assert(closingTxs.localOnly_opt.nonEmpty)
      assert(closingTxs.remoteOnly_opt.isEmpty)
      val localAndRemote = closingTxs.localAndRemote_opt.flatMap(_.toLocalOutput_opt).get
      assert(localAndRemote.publicKeyScript == localPubKeyScript)
      assert(localAndRemote.amount == 145_000.sat)
      val localOnly = closingTxs.localOnly_opt.flatMap(_.toLocalOutput_opt).get
      assert(localOnly.publicKeyScript == localPubKeyScript)
      assert(localOnly.amount == 145_000.sat)
    }
    {
      // Remote is using OP_RETURN (option_simple_close): we set their output amount to 0 sat.
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 1_500_000 msat)
      val remotePubKeyScript = Script.write(OP_RETURN :: OP_PUSHDATA(hex"deadbeef") :: Nil)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByUs(5_000 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.localAndRemote_opt.nonEmpty)
      assert(closingTxs.localOnly_opt.nonEmpty)
      assert(closingTxs.remoteOnly_opt.isEmpty)
      val localAndRemoteIndex = closingTxs.localAndRemote_opt.flatMap(_.toLocalOutputIndex_opt).get
      val localAndRemote = closingTxs.localAndRemote_opt.flatMap(_.toLocalOutput_opt).get
      assert(localAndRemote.publicKeyScript == localPubKeyScript)
      assert(localAndRemote.amount == 145_000.sat)
      val remoteOutput = closingTxs.localAndRemote_opt.get.tx.txOut((localAndRemoteIndex.toInt + 1) % 2)
      assert(remoteOutput.amount == 0.sat)
      assert(remoteOutput.publicKeyScript == remotePubKeyScript)
      val localOnly = closingTxs.localOnly_opt.flatMap(_.toLocalOutput_opt).get
      assert(localOnly.publicKeyScript == localPubKeyScript)
      assert(localOnly.amount == 145_000.sat)
    }
    {
      // Remote is using OP_RETURN (option_simple_close) and paying the fees: we set their output amount to 0 sat.
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 10_000_000 msat)
      val remotePubKeyScript = Script.write(OP_RETURN :: OP_PUSHDATA(hex"deadbeef") :: Nil)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByThem(5_000 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.localAndRemote_opt.nonEmpty)
      assert(closingTxs.localOnly_opt.nonEmpty)
      assert(closingTxs.remoteOnly_opt.isEmpty)
      val localAndRemoteIndex = closingTxs.localAndRemote_opt.flatMap(_.toLocalOutputIndex_opt).get
      val localAndRemote = closingTxs.localAndRemote_opt.flatMap(_.toLocalOutput_opt).get
      assert(localAndRemote.publicKeyScript == localPubKeyScript)
      assert(localAndRemote.amount == 150_000.sat)
      val remoteOutput = closingTxs.localAndRemote_opt.get.tx.txOut((localAndRemoteIndex.toInt + 1) % 2)
      assert(remoteOutput.amount == 0.sat)
      assert(remoteOutput.publicKeyScript == remotePubKeyScript)
      val localOnly = closingTxs.localOnly_opt.flatMap(_.toLocalOutput_opt).get
      assert(localOnly.publicKeyScript == localPubKeyScript)
      assert(localOnly.amount == 150_000.sat)
    }
    {
      // Same amounts, both outputs untrimmed, local is fundee:
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 150_000_000 msat)
      val closingTx = ClosingTx.createUnsignedTx(commitInput, localPubKeyScript, remotePubKeyScript, localPaysClosingFees = false, localDustLimit, 1000 sat, spec)
      assert(closingTx.tx.txOut.length == 2)
      assert(closingTx.toLocalOutput_opt.nonEmpty)
      val toLocal = closingTx.toLocalOutput_opt.get
      assert(toLocal.publicKeyScript == localPubKeyScript)
      assert(toLocal.amount == 150_000.sat)
      val toRemoteIndex = (closingTx.toLocalOutputIndex_opt.get + 1) % 2
      assert(closingTx.tx.txOut(toRemoteIndex.toInt).amount < 150_000.sat)
    }
    {
      // Their output is trimmed:
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 1_000 msat)
      val closingTx = ClosingTx.createUnsignedTx(commitInput, localPubKeyScript, remotePubKeyScript, localPaysClosingFees = false, localDustLimit, 1000 sat, spec)
      assert(closingTx.tx.txOut.length == 1)
      assert(closingTx.toLocalOutputIndex_opt.contains(0))
      assert(closingTx.toLocalOutput_opt.nonEmpty)
      val toLocal = closingTx.toLocalOutput_opt.get
      assert(toLocal.publicKeyScript == localPubKeyScript)
      assert(toLocal.amount == 150_000.sat)
    }
    {
      // Their output is trimmed (option_simple_close):
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 1_000_000 msat)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByThem(800 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.all.size == 1)
      assert(closingTxs.localOnly_opt.nonEmpty)
      val toLocal = closingTxs.localOnly_opt.flatMap(_.toLocalOutput_opt).get
      assert(toLocal.publicKeyScript == localPubKeyScript)
      assert(toLocal.amount == 150_000.sat)
      assert(closingTxs.localOnly_opt.flatMap(_.toLocalOutputIndex_opt).contains(0))
    }
    {
      // Their OP_RETURN output is trimmed (option_simple_close):
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 150_000_000 msat, 1_000_000 msat)
      val remotePubKeyScript = Script.write(OP_RETURN :: OP_PUSHDATA(hex"deadbeef") :: Nil)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByThem(1_001 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.all.size == 1)
      assert(closingTxs.localOnly_opt.nonEmpty)
      val toLocal = closingTxs.localOnly_opt.flatMap(_.toLocalOutput_opt).get
      assert(toLocal.publicKeyScript == localPubKeyScript)
      assert(toLocal.amount == 150_000.sat)
      assert(closingTxs.localOnly_opt.flatMap(_.toLocalOutputIndex_opt).contains(0))
    }
    {
      // Our output is trimmed:
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 50_000 msat, 150_000_000 msat)
      val closingTx = ClosingTx.createUnsignedTx(commitInput, localPubKeyScript, remotePubKeyScript, localPaysClosingFees = true, localDustLimit, 1000 sat, spec)
      assert(closingTx.tx.txOut.length == 1)
      assert(closingTx.toLocalOutput_opt.isEmpty)
    }
    {
      // Our output is trimmed (option_simple_close):
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 1_000_000 msat, 150_000_000 msat)
      val closingTxs = makeSimpleClosingTxs(commitInput, spec, SimpleClosingTxFee.PaidByUs(800 sat), 0, localPubKeyScript, remotePubKeyScript)
      assert(closingTxs.all.size == 1)
      assert(closingTxs.remoteOnly_opt.nonEmpty)
      assert(closingTxs.remoteOnly_opt.flatMap(_.toLocalOutput_opt).isEmpty)
    }
    {
      // Both outputs are trimmed:
      val spec = CommitmentSpec(Set.empty, feeratePerKw, 50_000 msat, 10_000 msat)
      val closingTx = ClosingTx.createUnsignedTx(commitInput, localPubKeyScript, remotePubKeyScript, localPaysClosingFees = true, localDustLimit, 1000 sat, spec)
      assert(closingTx.tx.txOut.isEmpty)
      assert(closingTx.toLocalOutput_opt.isEmpty)
    }
  }

}
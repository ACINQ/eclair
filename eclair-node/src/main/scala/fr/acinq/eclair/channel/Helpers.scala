package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar, sha256}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair.Features.Unset
import fr.acinq.eclair.{Features, NodeParams}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ClosingSigned, UpdateAddHtlc, UpdateFulfillHtlc}
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 20/05/2016.
  */

object Helpers {

  /**
    * Depending on the state, returns the current temporaryChannelId or channelId
    * @param stateData
    * @return
    */
  def getChannelId(stateData: Data): Long = stateData match {
    case Nothing => -1
    case d: DATA_WAIT_FOR_OPEN_CHANNEL => d.initFundee.temporaryChannelId
    case d: DATA_WAIT_FOR_ACCEPT_CHANNEL => d.initFunder.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_INTERNAL => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_CREATED => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_SIGNED => d.temporaryChannelId
    case d: HasCommitments => d.channelId
  }

  def validateParams(nodeParams: NodeParams, channelReserveSatoshis: Long, fundingSatoshis: Long): Unit = {
    val reserveToFundingRatio = channelReserveSatoshis.toDouble / fundingSatoshis
    require(reserveToFundingRatio <= nodeParams.maxReserveToFundingRatio, s"channelReserveSatoshis too high: ratio=$reserveToFundingRatio max=${nodeParams.maxReserveToFundingRatio}")
  }

  object Funding {

    def makeFundingInputInfo(fundingTxId: BinaryData, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): InputInfo = {
      val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
      InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex), fundingTxOut, write(fundingScript))
    }

    /**
      * Creates both sides's first commitment transaction
      *
      * @param localParams
      * @param remoteParams
      * @param pushMsat
      * @param fundingTxHash
      * @param fundingTxOutputIndex
      * @param remoteFirstPerCommitmentPoint
      * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
      */
    def makeFirstCommitTxs(localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, fundingTxHash: BinaryData, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: Point): (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx) = {
      val toLocalMsat = if (localParams.isFunder) fundingSatoshis * 1000 - pushMsat else pushMsat
      val toRemoteMsat = if (localParams.isFunder) pushMsat else fundingSatoshis * 1000 - pushMsat

      // local and remote feerate are the same at this point (funder gets to choose the initial feerate)
      val localSpec = CommitmentSpec(Set.empty[Htlc], feeRatePerKw = localParams.feeratePerKw, toLocalMsat = toLocalMsat, toRemoteMsat = toRemoteMsat)
      val remoteSpec = CommitmentSpec(Set.empty[Htlc], feeRatePerKw = remoteParams.feeratePerKw, toLocalMsat = toRemoteMsat, toRemoteMsat = toLocalMsat)

      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, Satoshi(fundingSatoshis), localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey)
      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      val (localCommitTx, _, _) = Commitments.makeLocalTxs(0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec)

      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }

    def announceChannel(localLocalFeatures: BinaryData, remoteLocalFeature: BinaryData): Boolean = {
      val localChannelPublic = Features.channelPublic(localLocalFeatures)
      val remoteChannelPublic = Features.channelPublic(remoteLocalFeature)
      if (localChannelPublic == Unset || remoteChannelPublic == Unset) false else true
    }

  }

  object Closing extends Logging {

    def isValidFinalScriptPubkey(scriptPubKey: BinaryData): Boolean = {
      Try(Script.parse(scriptPubKey)) match {
        case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => true
        case _ => false
      }
    }

    def makeFirstClosingTx(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData): ClosingSigned = {
      logger.info(s"making first closing tx with commitments:\n${Commitments.specs2String(commitments)}")
      import commitments._
      val closingFee = {
        // this is just to estimate the weight
        val dummyClosingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, Satoshi(0), Satoshi(0), localCommit.spec)
        val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, "aa" * 71, "bb" * 71).tx)
        Transactions.weight2fee(commitments.localParams.feeratePerKw, closingWeight)
      }
      val (_, closingSigned) = makeClosingTx(commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
      closingSigned
    }

    def makeClosingTx(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, closingFee: Satoshi): (ClosingTx, ClosingSigned) = {
      import commitments._
      require(isValidFinalScriptPubkey(localScriptPubkey), "invalid localScriptPubkey")
      require(isValidFinalScriptPubkey(remoteScriptPubkey), "invalid remoteScriptPubkey")
      // TODO: check that
      val dustLimitSatoshis = Satoshi(Math.max(localParams.dustLimitSatoshis, remoteParams.dustLimitSatoshis))
      val closingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, dustLimitSatoshis, closingFee, localCommit.spec)
      val localClosingSig = Transactions.sign(closingTx, commitments.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(channelId, closingFee.amount, localClosingSig)
      (closingTx, closingSigned)
    }

    def checkClosingSignature(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, closingFee: Satoshi, remoteClosingSig: BinaryData): Try[Transaction] = {
      import commitments._
      val (closingTx, closingSigned) = makeClosingTx(commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
      val signedClosingTx = Transactions.addSigs(closingTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, closingSigned.signature, remoteClosingSig)
      Transactions.checkSpendable(signedClosingTx).map(x => signedClosingTx.tx)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = {
      ((localClosingFee + remoteClosingFee) / 4) * 2 match {
        case value if value == localClosingFee => value + Satoshi(2)
        case value => value
      }
    }

    def generateTx(desc: String)(attempt: Try[TransactionWithInputInfo]): Option[TransactionWithInputInfo] = {
      attempt match {
        case Success(txinfo) =>
          logger.warn(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount.amount).sum} tx=${Transaction.write(txinfo.tx)}")
          Some(txinfo)
        case Failure(t) =>
          logger.warn(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
          None
      }
    }

    /**
      *
      * Claim all the HTLCs that we've received from our current commit tx. This will be
      * done using 2nd stage HTLC transactions
      *
      * @param commitments our commitment data, which include payment preimages
      * @return a list of transactions (one per HTLC that we can claim)
      */
    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, tx: Transaction): LocalCommitPublished = {
      import commitments._
      require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid, "txid mismatch, provided tx is not the current local commit tx")

      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = Generators.derivePrivKey(localParams.delayedPaymentKey, localPerCommitmentPoint)

      // first we will claim our main output as soon as the delay is over
      val mainDelayedTx = generateTx("main-delayed-output")(Try {
        val claimDelayed = Transactions.makeClaimDelayedOutputTx(tx, localRevocationPubkey, localParams.toSelfDelay, localDelayedPrivkey.publicKey, localParams.defaultFinalScriptPubKey, localCommit.spec.feeRatePerKw)
        val sig = Transactions.sign(claimDelayed, localDelayedPrivkey)
        Transactions.addSigs(claimDelayed, sig)
      })

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      val htlcTxes = localCommit.publishableTxs.htlcTxsAndSigs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case HtlcTxAndSigs(txinfo@HtlcSuccessTx(_, _, paymentHash), localSig, remoteSig) if preimages.exists(r => sha256(r) == paymentHash) =>
          generateTx("htlc-success")(Try {
            val preimage = preimages.find(r => sha256(r) == paymentHash).get
            Transactions.addSigs(txinfo, localSig, remoteSig, preimage)
          })

        // NB: regarding htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back

        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case HtlcTxAndSigs(txinfo: HtlcTimeoutTx, localSig, remoteSig) =>
          generateTx("htlc-timeout")(Try {
            Transactions.addSigs(txinfo, localSig, remoteSig)
          })
      }.flatten

      // all htlc output to us are delayed, so we need to claim them as soon as the delay is over
      val htlcDelayedTxes = htlcTxes.map {
        case txinfo: TransactionWithInputInfo => generateTx("claim-delayed-output")(Try {
          // TODO: we should use the current fee rate, not the initial fee rate that we get from localParams
          val claimDelayed = Transactions.makeClaimDelayedOutputTx(txinfo.tx, localRevocationPubkey, localParams.toSelfDelay, localDelayedPrivkey.publicKey, localParams.defaultFinalScriptPubKey, localParams.feeratePerKw)
          val sig = Transactions.sign(claimDelayed, localDelayedPrivkey)
          Transactions.addSigs(claimDelayed, sig)
        })
      }.flatten

      // OPTIONAL: let's check transactions are actually spendable
      //val txes = mainDelayedTx +: (htlcTxes ++ htlcDelayedTxes)
      //require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      LocalCommitPublished(
        commitTx = tx,
        claimMainDelayedOutputTx = mainDelayedTx.map(_.tx),
        htlcSuccessTxs = htlcTxes.collect { case c: HtlcSuccessTx => c.tx },
        htlcTimeoutTxs = htlcTxes.collect { case c: HtlcTimeoutTx => c.tx },
        claimHtlcDelayedTx = htlcDelayedTxes.map(_.tx))
    }

    /**
      *
      * Claim all the HTLCs that we've received from their current commit tx
      *
      * @param commitments our commitment data, which include payment preimages
      * @return a list of transactions (one per HTLC that we can claim)
      */
    def claimRemoteCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction): RemoteCommitPublished = {
      import commitments.{commitInput, localParams, remoteParams}
      require(remoteCommit.txid == tx.txid, "txid mismatch, provided tx is not the current remote commit tx")
      val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = Commitments.makeRemoteTxs(remoteCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
      require(remoteCommitTx.tx.txid == tx.txid, "txid mismatch, cannot recompute the current remote commit tx")

      val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, remoteCommit.remotePerCommitmentPoint)
      val localPrivkey = Generators.derivePrivKey(localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)

      // TODO: in the following we use localCommit.feeRatePerKw

      // first we will claim our main output right away
      val mainTx = generateTx("claim-p2wpkh-output")(Try {
        val claimMain = Transactions.makeClaimP2WPKHOutputTx(tx, localPrivkey.publicKey, localParams.defaultFinalScriptPubKey, commitments.localCommit.spec.feeRatePerKw)
        val sig = Transactions.sign(claimMain, localPrivkey)
        Transactions.addSigs(claimMain, localPrivkey.publicKey, sig)
      })

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa
      val txes = commitments.remoteCommit.spec.htlcs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case Htlc(OUT, add: UpdateAddHtlc, _) if preimages.exists(r => sha256(r) == add.paymentHash) => generateTx("claim-htlc-success")(Try {
          val preimage = preimages.find(r => sha256(r) == add.paymentHash).get
          val tx = Transactions.makeClaimHtlcSuccessTx(remoteCommitTx.tx, localPrivkey.publicKey, remotePubkey, localParams.defaultFinalScriptPubKey, add, commitments.localCommit.spec.feeRatePerKw)
          val sig = Transactions.sign(tx, localPrivkey)
          Transactions.addSigs(tx, sig, preimage)
        })
        // NB: incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back
        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case Htlc(IN, add: UpdateAddHtlc, _) => generateTx("claim-htlc-timeout")(Try {
          val tx = Transactions.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, localPrivkey.publicKey, remotePubkey, localParams.defaultFinalScriptPubKey, add, commitments.localCommit.spec.feeRatePerKw)
          val sig = Transactions.sign(tx, localPrivkey)
          Transactions.addSigs(tx, sig)
        })
      }.toSeq.flatten

      // OPTIONAL: let's check transactions are actually spendable
      //require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      RemoteCommitPublished(
        commitTx = tx,
        claimMainOutputTx = mainTx.map(_.tx),
        claimHtlcSuccessTxs = txes.collect { case c: ClaimHtlcSuccessTx => c.tx },
        claimHtlcTimeoutTxs = txes.collect { case c: ClaimHtlcTimeoutTx => c.tx }
      )

    }

    /**
      * When an unexpected transaction spending the funding tx is detected:
      * 1) we find out if the published transaction is one of remote's revoked txs
      * 2) and then:
      * a) if it is a revoked tx we build a set of transactions that will punish them by stealing all their funds
      * b) otherwise there is nothing we can do
      *
      * @return a [[RevokedCommitPublished]] object containing penalty transactions if the tx is a revoked commitment
      */
    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction): Option[RevokedCommitPublished] = {
      import commitments._
      require(tx.txIn.size == 1, "commitment tx should have 1 input")
      val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn(0).sequence, tx.lockTime)
      // this tx has been published by remote, so we need to invert local/remote params
      val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, remoteParams.paymentBasepoint, localParams.paymentKey.toPoint)
      require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
      logger.warn(s"counterparty has published revoked commit txnumber=$txnumber")
      // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
      remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber)
        .map(d => Scalar(d))
        .map { remotePerCommitmentSecret =>
          val remotePerCommitmentPoint = remotePerCommitmentSecret.toPoint

          val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
          val remoteRevocationPrivkey = Generators.revocationPrivKey(localParams.revocationSecret, remotePerCommitmentSecret)
          val localPrivkey = Generators.derivePrivKey(localParams.paymentKey, remotePerCommitmentPoint)

          // first we will claim our main output right away
          val mainTx = generateTx("claim-p2wpkh-output")(Try {
            val claimMain = Transactions.makeClaimP2WPKHOutputTx(tx, localPrivkey.publicKey, localParams.defaultFinalScriptPubKey, localCommit.spec.feeRatePerKw)
            val sig = Transactions.sign(claimMain, localPrivkey)
            Transactions.addSigs(claimMain, localPrivkey.publicKey, sig)
          })

          // then we punish them by stealing their main output
          val mainPenaltyTx = generateTx("main-penalty")(Try {
            // TODO: we should use the current fee rate, not the initial fee rate that we get from localParams
            val txinfo = Transactions.makeMainPenaltyTx(tx, remoteRevocationPrivkey.publicKey, localParams.defaultFinalScriptPubKey, remoteParams.toSelfDelay, remoteDelayedPubkey, commitments.localParams.feeratePerKw)
            val sig = Transactions.sign(txinfo, remoteRevocationPrivkey)
            Transactions.addSigs(txinfo, sig)
          })

          // TODO: we don't claim htlcs outputs yet

          // OPTIONAL: let's check transactions are actually spendable
          //val txes = mainDelayedRevokedTx :: Nil
          //require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

          RevokedCommitPublished(
            commitTx = tx,
            claimMainOutputTx = mainTx.map(_.tx),
            mainPenaltyTx = mainPenaltyTx.map(_.tx),
            claimHtlcTimeoutTxs = Nil,
            htlcTimeoutTxs = Nil,
            htlcPenaltyTxs = Nil
          )
        }
    }

  }

}
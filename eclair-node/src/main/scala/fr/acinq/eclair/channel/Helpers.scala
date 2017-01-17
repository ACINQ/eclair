package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.LightningCrypto.sha256
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.{ClosingSigned, UpdateAddHtlc, UpdateFulfillHtlc}

import scala.util.Try

/**
  * Created by PM on 20/05/2016.
  */

object Helpers {

  object Funding {

    def makeFundingInputInfo(fundingTxId: BinaryData, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: BinaryData, fundingPubkey2: BinaryData): InputInfo = {
      val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
      InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex), fundingTxOut, write(fundingScript))
    }

    /**
      * Creates both sides's first commitment transaction
      *
      * @param params
      * @param pushMsat
      * @param fundingTxHash
      * @param fundingTxOutputIndex
      * @param remoteFirstPerCommitmentPoint
      * @return (localSpec, localTx, remoteSpec, remoteTx, fundingTxOutput)
      */
    def makeFirstCommitTxs(params: ChannelParams, pushMsat: Long, fundingTxHash: BinaryData, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: BinaryData): (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx) = {
      val toLocalMsat = if (params.localParams.isFunder) params.fundingSatoshis * 1000 - pushMsat else pushMsat
      val toRemoteMsat = if (params.localParams.isFunder) pushMsat else params.fundingSatoshis * 1000 - pushMsat

      // local and remote feerate are the same at this point (funder gets to choose the initial feerate)
      val localSpec = CommitmentSpec(Set.empty[Htlc], feeRatePerKw = params.localParams.feeratePerKw, toLocalMsat = toLocalMsat, toRemoteMsat = toRemoteMsat)
      val remoteSpec = CommitmentSpec(Set.empty[Htlc], feeRatePerKw = params.remoteParams.feeratePerKw, toLocalMsat = toRemoteMsat, toRemoteMsat = toLocalMsat)

      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, Satoshi(params.fundingSatoshis), params.localParams.fundingPrivKey.toPoint, params.remoteParams.fundingPubKey)
      val localPerCommitmentPoint = Generators.perCommitPoint(params.localParams.shaSeed, 0)
      val (localCommitTx, _, _) = Commitments.makeLocalTxs(0, params.localParams, params.remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(0, params.localParams, params.remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec)

      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }

  }

  object Closing {

    def makeFirstClosingTx(params: ChannelParams, commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData): ClosingSigned = {
      // TODO: check that
      val dustLimitSatoshis = Satoshi(Math.max(commitments.localParams.dustLimitSatoshis, commitments.remoteParams.dustLimitSatoshis))
      // TODO
      val closingFee = Satoshi(20000)
      // TODO: check commitments.localCommit.spec == commitments.remoteCommit.spec
      val closingTx = Transactions.makeClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey, commitments.localParams.isFunder, dustLimitSatoshis, closingFee, commitments.localCommit.spec)
      val localClosingSig = Transactions.sign(closingTx, params.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      closingSigned
    }

    def makeClosingTx(params: ChannelParams, commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, closingFee: Satoshi): (ClosingTx, ClosingSigned) = {
      // TODO: check that
      val dustLimitSatoshis = Satoshi(Math.max(commitments.localParams.dustLimitSatoshis, commitments.remoteParams.dustLimitSatoshis))
      // TODO: check commitments.localCommit.spec == commitments.remoteCommit.spec
      val closingTx = Transactions.makeClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey, commitments.localParams.isFunder, dustLimitSatoshis, closingFee, commitments.localCommit.spec)
      val localClosingSig = Transactions.sign(closingTx, params.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      (closingTx, closingSigned)
    }

    def checkClosingSignature(params: ChannelParams, commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, closingFee: Satoshi, remoteClosingSig: BinaryData): Try[Transaction] = {
      // TODO: check that
      val dustLimitSatoshis = Satoshi(Math.max(commitments.localParams.dustLimitSatoshis, commitments.remoteParams.dustLimitSatoshis))
      val closingTx = Transactions.makeClosingTx(commitments.commitInput, localScriptPubkey, remoteScriptPubkey, commitments.localParams.isFunder, dustLimitSatoshis, closingFee, commitments.localCommit.spec)
      val localClosingSig = Transactions.sign(closingTx, params.localParams.fundingPrivKey)
      val signedClosingTx = Transactions.addSigs(closingTx, commitments.localParams.fundingPrivKey.toPoint, commitments.remoteParams.fundingPubKey, localClosingSig, remoteClosingSig)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      Transactions.checkSpendable(signedClosingTx).map(x => signedClosingTx.tx)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = {
      ((localClosingFee + remoteClosingFee) / 4) * 2 match {
        case value if value == localClosingFee => value + Satoshi(2)
        case value => value
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
    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, tx: Transaction): Seq[TransactionWithInputInfo] = {
      import commitments._
      require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid, "txid mismatch, provided tx is not the current local commit tx")

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      val htlcTxes = localCommit.publishableTxs.htlcTxsAndSigs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case HtlcTxAndSigs(txinfo@HtlcSuccessTx(_, _, paymentHash), localSig, remoteSig) if preimages.exists(r => sha256(r) == paymentHash) =>
          val preimage = preimages.find(r => sha256(r) == paymentHash).get
          Transactions.addSigs(txinfo, localSig, remoteSig, preimage)

        // NB: regarding htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back

        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case HtlcTxAndSigs(txinfo: HtlcTimeoutTx, localSig, remoteSig) =>
          Transactions.addSigs(txinfo, localSig, remoteSig)
      }

      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt)
      val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, localPerCommitmentPoint)
      val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = Generators.derivePrivKey(localParams.delayedPaymentKey, localPerCommitmentPoint)

      // TODO: final key is the payment pubkey so that it matches the main outputs, is that the best option?
      val delayedTxes = htlcTxes.map {
        case txinfo: TransactionWithInputInfo =>
          val claimDelayed = Transactions.makeClaimHtlcDelayed(txinfo.tx, localRevocationPubkey, localParams.toSelfDelay, localDelayedPrivkey.toPoint, localPubkey)
          val sig = Transactions.sign(claimDelayed, localDelayedPrivkey)
          Transactions.addSigs(claimDelayed, sig)
      }

      val txes = htlcTxes ++ delayedTxes

      // OPTIONAL: let's check transactions are actually spendable
      require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      txes
    }

    /**
      *
      * Claim all the HTLCs that we've received from their current commit tx
      *
      * @param commitments our commitment data, which include payment preimages
      * @return a list of transactions (one per HTLC that we can claim)
      */
    def claimCurrentRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction): Seq[TransactionWithInputInfo] = {
      import commitments._
      require(remoteCommit.txid == tx.txid, "txid mismatch, provided tx is not the current remote commit tx")
      val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = Commitments.makeRemoteTxs(remoteCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
      require(remoteCommitTx.tx.txid == tx.txid, "txid mismatch, cannot recompute the current remote commit tx")

      val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, remoteCommit.remotePerCommitmentPoint)
      val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remoteCommit.remotePerCommitmentPoint)
      val localPrivkey = Generators.derivePrivKey(localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      // TODO: final key is the payment pubkey so that it matches the main outputs, is that the best option?


      // TODO: don't handle dust!!!!
      // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa
      val txes = commitments.remoteCommit.spec.htlcs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case Htlc(OUT, add: UpdateAddHtlc, _) if preimages.exists(r => sha256(r) == add.paymentHash) =>
          val preimage = preimages.find(r => sha256(r) == add.paymentHash).get
          val tx = Transactions.makeClaimHtlcSuccessTx(remoteCommitTx.tx, localPubkey, remoteDelayedPubkey, localPubkey, add)
          val sig = Transactions.sign(tx, localPrivkey)
          Transactions.addSigs(tx, sig, preimage)
        // NB: incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back
        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case Htlc(IN, add: UpdateAddHtlc, _) =>
          val tx = Transactions.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, localPubkey, remoteDelayedPubkey, localPubkey, add)
          val sig = Transactions.sign(tx, localPrivkey)
          Transactions.addSigs(tx, sig)
      }

      // OPTIONAL: let's check transactions are actually spendable
      require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      txes.toSeq
    }

    /**
      * In reaction to the counterparty publishing a revoked commitment tx, we punish them by
      */
    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction): Try[Seq[TransactionWithInputInfo]] = Try {
      import commitments._
      require(tx.txIn.size == 1, "commitment tx should have 1 input")
      val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn(0).sequence, tx.lockTime)
      // this tx has been published by remote, so we need to invert local/remote params
      val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, remoteParams.paymentBasepoint, localParams.paymentKey.toPoint)
      require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
      // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
      val remotePerCommitmentSecret = remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFFFFFL - txnumber).map(d => Scalar(d :+ 1.toByte)).getOrElse(throw new RuntimeException(s"cannot get commitment secret for txnumber=$txnumber"))
      val remotePerCommitmentPoint = remotePerCommitmentSecret.toPoint

      val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, remotePerCommitmentPoint)
      val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
      val remoteRevocationPrivkey = Generators.revocationPrivKey(localParams.revocationSecret, remotePerCommitmentSecret)

      // TODO: final key is the payment pubkey so that it matches the main outputs, is that the best option?

      // let's punish remote by stealing its main output
      val mainDelayedRevokedTx = {
        val txinfo = Transactions.makeMainPunishmentTx(tx, remoteRevocationPrivkey.toPoint, localPubkey, remoteParams.toSelfDelay, remoteDelayedPubkey)
        val sig = Transactions.sign(txinfo, remoteRevocationPrivkey)
        Transactions.addSigs(txinfo, sig)
      }

      // TODO: we don't claim htlcs outputs yet

      val txes = mainDelayedRevokedTx :: Nil

      // OPTIONAL: let's check transactions are actually spendable
      require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      txes
    }

  }

}
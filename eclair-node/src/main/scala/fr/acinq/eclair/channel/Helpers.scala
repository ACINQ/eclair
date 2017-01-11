package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, CommitTx, InputInfo}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ClosingSigned

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
      * Claim a revoked commit tx using the matching revocation preimage, which allows us to claim all its inputs without a
      * delay
      *
      * @param theirTxTemplate    revoked commit tx template
      * @param revocationPreimage revocation preimage (which must match this specific commit tx)
      * @param privateKey         private key to send the claimed funds to (the returned tx will include a single P2WPKH output)
      * @return a signed transaction that spends the revoked commit tx
      */
    def claimRevokedCommitTx(theirTxTemplate: CommitTx, revocationPreimage: BinaryData, privateKey: BinaryData): Transaction = ???

    /*{
      val theirTx = theirTxTemplate.makeTx
      val outputs = collection.mutable.ListBuffer.empty[TxOut]

      // first, find out how much we can claim
      val outputsToClaim = (theirTxTemplate.localOutput.toSeq ++ theirTxTemplate.htlcReceivedOutputs ++ theirTxTemplate.htlcOfferedOutputs).filter(o => theirTx.txOut.indexOf(o.txOut) != -1)
      val totalAmount = outputsToClaim.map(_.amount).sum // TODO: substract a small network fee

      // create a tx that sends everything to our private key
      val tx = Transaction(version = 2,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(totalAmount, pay2wpkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
        lockTime = 0)

      // create tx inputs that spend each output that we can spend
      val inputs = outputsToClaim.map(outputTemplate => {
        val index = theirTx.txOut.indexOf(outputTemplate.txOut)
        TxIn(OutPoint(theirTx, index), signatureScript = BinaryData.empty, sequence = 0xffffffffL)
      })
      assert(inputs.length == outputsToClaim.length)

      // and sign them
      val tx1 = tx.copy(txIn = inputs)
      val witnesses = for (i <- 0 until tx1.txIn.length) yield {
        val sig = Transaction.signInput(tx1, i, outputsToClaim(i).redeemScript, SIGHASH_ALL, outputsToClaim(i).amount, 1, privateKey)
        val witness = ScriptWitness(sig :: revocationPreimage :: outputsToClaim(i).redeemScript :: Nil)
        witness
      }

      tx1.updateWitnesses(witnesses)
    }*/

    /**
      * claim an HTLC that we received using its payment preimage. This is used only when the other party publishes its
      * current commit tx which contains pending HTLCs.
      *
      * @param tx              commit tx published by the other party
      * @param htlcTemplate    HTLC template for an HTLC in the commit tx for which we have the preimage
      * @param paymentPreimage HTLC preimage
      * @param privateKey      private key which matches the pubkey  that the HTLC was sent to
      * @return a signed transaction that spends the HTLC in their published commit tx.
      *         This tx is not spendable right away: it has both an absolute CLTV time-out and a relative CSV time-out
      *         before which it can be published
      */
    //def claimReceivedHtlc(tx: Transaction, htlcTemplate: ReceivedHTLC, paymentPreimage: BinaryData, privateKey: BinaryData): Transaction = ???
    /*{
      require(htlcTemplate.htlc.add.paymentHash == BinaryData(Crypto.sha256(paymentPreimage)), "invalid payment preimage")
      // find its index in their tx
      val index = tx.txOut.indexOf(htlcTemplate.txOut)

      val tx1 = Transaction(version = 2,
        txIn = TxIn(OutPoint(tx, index), BinaryData.empty, sequence = Common.toSelfDelay2csv(htlcTemplate.delay)) :: Nil,
        txOut = TxOut(htlcTemplate.amount, Common.pay2pkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
        lockTime = ??? /*Scripts.locktime2long_cltv(htlcTemplate.htlc.add.expiry)*/)

      val sig = Transaction.signInput(tx1, 0, htlcTemplate.redeemScript, SIGHASH_ALL, htlcTemplate.amount, 1, privateKey)
      val witness = ScriptWitness(sig :: paymentPreimage :: htlcTemplate.redeemScript :: Nil)
      val tx2 = tx1.updateWitness(0, witness)
      tx2
    }*/

    /**
      * claim all the HTLCs that we've received from their current commit tx
      *
      * @param txTemplate  commit tx published by the other party
      * @param commitments our commitment data, which include payment preimages
      * @return a list of transactions (one per HTLC that we can claim)
      */
    //def claimReceivedHtlcs(tx: Transaction, txTemplate: CommitTxTemplate, commitments: Commitments): Seq[Transaction] = ???
    /*{
      val preImages = commitments.localChanges.all.collect { case UpdateFulfillHtlc(_, id, paymentPreimage) => paymentPreimage }
      // TODO: FIXME !!!
      //val htlcTemplates = txTemplate.htlcSent
      val htlcTemplates = txTemplate.htlcReceived ++ txTemplate.htlcSent

      //@tailrec
      def loop(htlcs: Seq[HTLCTemplate], acc: Seq[Transaction] = Seq.empty[Transaction]): Seq[Transaction] = Nil

      /*{
      htlcs.headOption match {
        case Some(head) =>
          preImages.find(preImage => head.htlc.add.rHash == bin2sha256(Crypto.sha256(preImage))) match {
            case Some(preImage) => loop(htlcs.tail, claimReceivedHtlc(tx, head, preImage, commitments.ourParams.finalPrivKey) +: acc)
            case None => loop(htlcs.tail, acc)
          }
        case None => acc
      }
    }*/
      loop(htlcTemplates)
    }*/

    //def claimSentHtlc(tx: Transaction, htlcTemplate: OfferedHTLCOutputTemplate, privateKey: BinaryData): Transaction = ???
    /*{
      val index = tx.txOut.indexOf(htlcTemplate.txOut)
      val tx1 = Transaction(
        version = 2,
        txIn = TxIn(OutPoint(tx, index), Array.emptyByteArray, sequence = Common.toSelfDelay2csv(htlcTemplate.delay)) :: Nil,
        txOut = TxOut(htlcTemplate.amount, Common.pay2pkh(Crypto.publicKeyFromPrivateKey(privateKey))) :: Nil,
        lockTime = ??? /*Scripts.locktime2long_cltv(htlcTemplate.htlc.add.expiry)*/)

      val sig = Transaction.signInput(tx1, 0, htlcTemplate.redeemScript, SIGHASH_ALL, htlcTemplate.amount, 1, privateKey)
      val witness = ScriptWitness(sig :: Hash.Zeroes :: htlcTemplate.redeemScript :: Nil)
      tx1.updateWitness(0, witness)
    }*/

    // TODO: fix this!
    //def claimSentHtlcs(tx: Transaction, txTemplate: CommitTxTemplate, commitments: Commitments): Seq[Transaction] = Nil

    /*{
    // txTemplate could be our template (we published our commit tx) or their template (they published their commit tx)
    val htlcs1 = txTemplate.htlcSent.filter(_.ourKey == commitments.ourParams.finalPubKey)
    val htlcs2 = txTemplate.htlcReceived.filter(_.theirKey == commitments.ourParams.finalPubKey)
    val htlcs = htlcs1 ++ htlcs2
    htlcs.map(htlcTemplate => claimSentHtlc(tx, htlcTemplate, commitments.ourParams.finalPrivKey))
  }*/
  }

}
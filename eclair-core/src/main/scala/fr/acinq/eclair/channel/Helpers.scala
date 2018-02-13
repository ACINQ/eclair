package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar, sha256}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.{OutPoint, _}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams}

import scala.concurrent.Await
import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 20/05/2016.
  */

object Helpers {

  /**
    * Depending on the state, returns the current temporaryChannelId or channelId
    *
    * @param stateData
    * @return
    */
  def getChannelId(stateData: Data): BinaryData = stateData match {
    case Nothing => BinaryData("00" * 32)
    case d: DATA_WAIT_FOR_OPEN_CHANNEL => d.initFundee.temporaryChannelId
    case d: DATA_WAIT_FOR_ACCEPT_CHANNEL => d.initFunder.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_INTERNAL => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_CREATED => d.temporaryChannelId
    case d: DATA_WAIT_FOR_FUNDING_SIGNED => d.channelId
    case d: HasCommitments => d.channelId
  }

  /**
    * Called by the fundee
    */
  def validateParamsFundee(nodeParams: NodeParams, open: OpenChannel): Unit = {
    if (nodeParams.chainHash != open.chainHash) throw new InvalidChainHash(open.temporaryChannelId, local = nodeParams.chainHash, remote = open.chainHash)
    if (open.fundingSatoshis < Channel.MIN_FUNDING_SATOSHIS || open.fundingSatoshis >= Channel.MAX_FUNDING_SATOSHIS) throw new InvalidFundingAmount(open.temporaryChannelId, open.fundingSatoshis, Channel.MIN_FUNDING_SATOSHIS, Channel.MAX_FUNDING_SATOSHIS)
    if (open.pushMsat > 1000 * open.fundingSatoshis) throw new InvalidPushAmount(open.temporaryChannelId, open.pushMsat, 1000 * open.fundingSatoshis)
    val localFeeratePerKw = Globals.feeratesPerKw.get.block_1
    if (isFeeDiffTooHigh(open.feeratePerKw, localFeeratePerKw, nodeParams.maxFeerateMismatch)) throw new FeerateTooDifferent(open.temporaryChannelId, localFeeratePerKw, open.feeratePerKw)
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (open.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) throw new InvalidDustLimit(open.temporaryChannelId, open.dustLimitSatoshis, Channel.MIN_DUSTLIMIT)
    }
    val reserveToFundingRatio = open.channelReserveSatoshis.toDouble / Math.max(open.fundingSatoshis, 1)
    if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) throw new ChannelReserveTooHigh(open.temporaryChannelId, open.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio)
  }

  /**
    * Called by the funder
    */
  def validateParamsFunder(nodeParams: NodeParams, open: OpenChannel, accept: AcceptChannel): Unit = {
    if (accept.maxAcceptedHtlcs > Channel.MAX_ACCEPTED_HTLCS) throw new InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, accept.maxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS)
    // only enforce dust limit check on mainnet
    if (nodeParams.chainHash == Block.LivenetGenesisBlock.hash) {
      if (accept.dustLimitSatoshis < Channel.MIN_DUSTLIMIT) throw new InvalidDustLimit(accept.temporaryChannelId, accept.dustLimitSatoshis, Channel.MIN_DUSTLIMIT)
    }
    val reserveToFundingRatio = accept.channelReserveSatoshis.toDouble / Math.max(open.fundingSatoshis, 1)
    if (reserveToFundingRatio > nodeParams.maxReserveToFundingRatio) throw new ChannelReserveTooHigh(open.temporaryChannelId, accept.channelReserveSatoshis, reserveToFundingRatio, nodeParams.maxReserveToFundingRatio)
  }

  /**
    *
    * @param remoteFeeratePerKw remote fee rate per kiloweight
    * @param localFeeratePerKw  local fee rate per kiloweight
    * @return the "normalized" difference between local and remote fee rate, i.e. |remote - local| / avg(local, remote)
    */
  def feeRateMismatch(remoteFeeratePerKw: Long, localFeeratePerKw: Long): Double =
    Math.abs((2.0 * (remoteFeeratePerKw - localFeeratePerKw)) / (localFeeratePerKw + remoteFeeratePerKw))

  def shouldUpdateFee(commitmentFeeratePerKw: Long, networkFeeratePerKw: Long, updateFeeMinDiffRatio: Double): Boolean =
  // negative feerate can happen in regtest mode
    networkFeeratePerKw > 0 && feeRateMismatch(networkFeeratePerKw, commitmentFeeratePerKw) > updateFeeMinDiffRatio

  /**
    *
    * @param remoteFeeratePerKw      remote fee rate per kiloweight
    * @param localFeeratePerKw       local fee rate per kiloweight
    * @param maxFeerateMismatchRatio maximum fee rate mismatch ratio
    * @return true if the difference between local and remote fee rates is too high.
    *         the actual check is |remote - local| / avg(local, remote) > mismatch ratio
    */
  def isFeeDiffTooHigh(remoteFeeratePerKw: Long, localFeeratePerKw: Long, maxFeerateMismatchRatio: Double): Boolean = {
    // negative feerate can happen in regtest mode
    remoteFeeratePerKw > 0 && feeRateMismatch(remoteFeeratePerKw, localFeeratePerKw) > maxFeerateMismatchRatio
  }

  def makeAnnouncementSignatures(nodeParams: NodeParams, commitments: Commitments, shortChannelId: Long) = {
    // TODO: empty features
    val features = BinaryData("")
    val (localNodeSig, localBitcoinSig) = Announcements.signChannelAnnouncement(nodeParams.chainHash, shortChannelId, nodeParams.privateKey, commitments.remoteParams.nodeId, commitments.localParams.fundingPrivKey, commitments.remoteParams.fundingPubKey, features)
    AnnouncementSignatures(commitments.channelId, shortChannelId, localNodeSig, localBitcoinSig)
  }

  def getFinalScriptPubKey(wallet: EclairWallet): BinaryData = {
    import scala.concurrent.duration._
    val finalAddress = Await.result(wallet.getFinalAddress, 40 seconds)
    val finalScriptPubKey = Base58Check.decode(finalAddress) match {
      case (Base58.Prefix.PubkeyAddressTestnet, hash) => Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(hash) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
      case (Base58.Prefix.ScriptAddressTestnet, hash) => Script.write(OP_HASH160 :: OP_PUSHDATA(hash) :: OP_EQUAL :: Nil)
    }
    finalScriptPubKey
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
    def makeFirstCommitTxs(temporaryChannelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, fundingTxHash: BinaryData, fundingTxOutputIndex: Int, remoteFirstPerCommitmentPoint: Point, maxFeerateMismatch: Double): (CommitmentSpec, CommitTx, CommitmentSpec, CommitTx) = {
      val toLocalMsat = if (localParams.isFunder) fundingSatoshis * 1000 - pushMsat else pushMsat
      val toRemoteMsat = if (localParams.isFunder) pushMsat else fundingSatoshis * 1000 - pushMsat

      val localSpec = CommitmentSpec(Set.empty[DirectedHtlc], feeratePerKw = initialFeeratePerKw, toLocalMsat = toLocalMsat, toRemoteMsat = toRemoteMsat)
      val remoteSpec = CommitmentSpec(Set.empty[DirectedHtlc], feeratePerKw = initialFeeratePerKw, toLocalMsat = toRemoteMsat, toRemoteMsat = toLocalMsat)

      if (!localParams.isFunder) {
        // they are funder, therefore they pay the fee: we need to make sure they can afford it!
        val toRemoteMsat = remoteSpec.toLocalMsat
        val fees = Transactions.commitTxFee(Satoshi(remoteParams.dustLimitSatoshis), remoteSpec).amount
        val missing = toRemoteMsat / 1000 - localParams.channelReserveSatoshis - fees
        if (missing < 0) {
          throw CannotAffordFees(temporaryChannelId, missingSatoshis = -1 * missing, reserveSatoshis = localParams.channelReserveSatoshis, feesSatoshis = fees)
        }
      }

      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex, Satoshi(fundingSatoshis), localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey)
      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, 0)
      val (localCommitTx, _, _) = Commitments.makeLocalTxs(0, localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _) = Commitments.makeRemoteTxs(0, localParams, remoteParams, commitmentInput, remoteFirstPerCommitmentPoint, remoteSpec)

      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }

  }

  object Closing {

    def isValidFinalScriptPubkey(scriptPubKey: BinaryData): Boolean = {
      Try(Script.parse(scriptPubKey)) match {
        case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => true
        case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => true
        case _ => false
      }
    }

    def firstClosingFee(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData)(implicit log: LoggingAdapter): Satoshi = {
      import commitments._
      // this is just to estimate the weight, it depends on size of the pubkey scripts
      val dummyClosingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, Satoshi(0), Satoshi(0), localCommit.spec)
      val closingWeight = Transaction.weight(Transactions.addSigs(dummyClosingTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, "aa" * 71, "bb" * 71).tx)
      // no need to use a very high fee here, so we target 6 blocks; also, we "MUST set fee_satoshis less than or equal to the base fee of the final commitment transaction"
      val feeratePerKw = Math.min(Globals.feeratesPerKw.get.blocks_6, commitments.localCommit.spec.feeratePerKw)
      log.info(s"using feeratePerKw=$feeratePerKw for initial closing tx")
      Transactions.weight2fee(feeratePerKw, closingWeight)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi = ((localClosingFee + remoteClosingFee) / 4) * 2

    def makeFirstClosingTx(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
      val closingFee = firstClosingFee(commitments, localScriptPubkey, remoteScriptPubkey)
      makeClosingTx(commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
    }

    def makeClosingTx(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, closingFee: Satoshi)(implicit log: LoggingAdapter): (ClosingTx, ClosingSigned) = {
      import commitments._
      require(isValidFinalScriptPubkey(localScriptPubkey), "invalid localScriptPubkey")
      require(isValidFinalScriptPubkey(remoteScriptPubkey), "invalid remoteScriptPubkey")
      log.debug(s"making closing tx with closingFee={} and commitments:\n{}", closingFee, Commitments.specs2String(commitments))
      // TODO: check that
      val dustLimitSatoshis = Satoshi(Math.max(localParams.dustLimitSatoshis, remoteParams.dustLimitSatoshis))
      val closingTx = Transactions.makeClosingTx(commitInput, localScriptPubkey, remoteScriptPubkey, localParams.isFunder, dustLimitSatoshis, closingFee, localCommit.spec)
      val localClosingSig = Transactions.sign(closingTx, commitments.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(channelId, closingFee.amount, localClosingSig)
      log.info(s"signed closing txid=${closingTx.tx.txid} with closingFeeSatoshis=${closingSigned.feeSatoshis}")
      log.debug(s"closingTxid=${closingTx.tx.txid} closingTx=${closingTx.tx}}")
      (closingTx, closingSigned)
    }

    def checkClosingSignature(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData, remoteClosingFee: Satoshi, remoteClosingSig: BinaryData)(implicit log: LoggingAdapter): Try[Transaction] = {
      import commitments._
      val lastCommitFeeSatoshi = commitments.commitInput.txOut.amount.amount - commitments.localCommit.publishableTxs.commitTx.tx.txOut.map(_.amount.amount).sum
      if (remoteClosingFee.amount > lastCommitFeeSatoshi) {
        log.error(s"remote proposed a commit fee higher than the last commitment fee: remoteClosingFeeSatoshi=${remoteClosingFee.amount} lastCommitFeeSatoshi=$lastCommitFeeSatoshi")
        throw new InvalidCloseFee(commitments.channelId, remoteClosingFee.amount)
      }
      val (closingTx, closingSigned) = makeClosingTx(commitments, localScriptPubkey, remoteScriptPubkey, remoteClosingFee)
      val signedClosingTx = Transactions.addSigs(closingTx, localParams.fundingPrivKey.publicKey, remoteParams.fundingPubKey, closingSigned.signature, remoteClosingSig)
      Transactions.checkSpendable(signedClosingTx).map(x => signedClosingTx.tx).recover { case _ => throw InvalidCloseSignature(commitments.channelId, signedClosingTx.tx) }
    }

    def generateTx(desc: String)(attempt: Try[TransactionWithInputInfo])(implicit log: LoggingAdapter): Option[TransactionWithInputInfo] = {
      attempt match {
        case Success(txinfo) =>
          log.info(s"tx generation success: desc=$desc txid=${txinfo.tx.txid} amount=${txinfo.tx.txOut.map(_.amount.amount).sum} tx=${txinfo.tx}")
          Some(txinfo)
        case Failure(t: TxGenerationSkipped) =>
          log.info(s"tx generation skipped: desc=$desc reason: ${t.getMessage}")
          None
        case Failure(t) =>
          log.warning(s"tx generation failure: desc=$desc reason: ${t.getMessage}")
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
    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, tx: Transaction)(implicit log: LoggingAdapter): LocalCommitPublished = {
      import commitments._
      require(localCommit.publishableTxs.commitTx.tx.txid == tx.txid, "txid mismatch, provided tx is not the current local commit tx")

      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = Generators.derivePrivKey(localParams.delayedPaymentKey, localPerCommitmentPoint)

      // no need to use a high fee rate for delayed transactions (we are the only one who can spend them)
      val feeratePerKwDelayed = Globals.feeratesPerKw.get.blocks_6

      // first we will claim our main output as soon as the delay is over
      val mainDelayedTx = generateTx("main-delayed-output")(Try {
        val claimDelayed = Transactions.makeClaimDelayedOutputTx(tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPrivkey.publicKey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed)
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

        // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

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
          val claimDelayed = Transactions.makeClaimDelayedOutputTx(txinfo.tx, Satoshi(localParams.dustLimitSatoshis), localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPrivkey.publicKey, localParams.defaultFinalScriptPubKey, feeratePerKwDelayed)
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
        claimHtlcDelayedTx = htlcDelayedTxes.map(_.tx),
        irrevocablySpent = Map.empty)
    }

    /**
      *
      * Claim all the HTLCs that we've received from their current commit tx
      *
      * @param commitments our commitment data, which include payment preimages
      * @return a list of transactions (one per HTLC that we can claim)
      */
    def claimRemoteCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit, tx: Transaction)(implicit log: LoggingAdapter): RemoteCommitPublished = {
      import commitments.{commitInput, localParams, remoteParams}
      require(remoteCommit.txid == tx.txid, "txid mismatch, provided tx is not the current remote commit tx")
      val (remoteCommitTx, htlcTimeoutTxs, htlcSuccessTxs) = Commitments.makeRemoteTxs(remoteCommit.index, localParams, remoteParams, commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)
      require(remoteCommitTx.tx.txid == tx.txid, "txid mismatch, cannot recompute the current remote commit tx")

      val localPaymentPrivkey = Generators.derivePrivKey(localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)
      val localHtlcPrivkey = Generators.derivePrivKey(localParams.htlcKey, remoteCommit.remotePerCommitmentPoint)
      val remoteHtlcPubkey = Generators.derivePubKey(remoteParams.htlcBasepoint, remoteCommit.remotePerCommitmentPoint)
      val localPerCommitmentPoint = Generators.perCommitPoint(localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubKey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val remoteRevocationPubkey = Generators.revocationPubKey(localParams.revocationBasepoint, remoteCommit.remotePerCommitmentPoint)

      // no need to use a high fee rate for our main output (we are the only one who can spend it)
      val feeratePerKwMain = Globals.feeratesPerKw.get.blocks_6
      // we need to use a rather high fee for htlc-claim because we compete with the counterparty
      val feeratePerKwHtlc = Globals.feeratesPerKw.get.block_1

      // first we will claim our main output right away
      val mainTx = generateTx("claim-p2wpkh-output")(Try {
        val claimMain = Transactions.makeClaimP2WPKHOutputTx(tx, Satoshi(localParams.dustLimitSatoshis), localPaymentPrivkey.publicKey, localParams.defaultFinalScriptPubKey, feeratePerKwMain)
        val sig = Transactions.sign(claimMain, localPaymentPrivkey)
        Transactions.addSigs(claimMain, localPaymentPrivkey.publicKey, sig)
      })

      // those are the preimages to existing received htlcs
      val preimages = commitments.localChanges.all.collect { case u: UpdateFulfillHtlc => u.paymentPreimage }

      // remember we are looking at the remote commitment so IN for them is really OUT for us and vice versa
      val txes = commitments.remoteCommit.spec.htlcs.collect {
        // incoming htlc for which we have the preimage: we spend it directly
        case DirectedHtlc(OUT, add: UpdateAddHtlc) if preimages.exists(r => sha256(r) == add.paymentHash) => generateTx("claim-htlc-success")(Try {
          val preimage = preimages.find(r => sha256(r) == add.paymentHash).get
          val tx = Transactions.makeClaimHtlcSuccessTx(remoteCommitTx.tx, Satoshi(localParams.dustLimitSatoshis), localHtlcPrivkey.publicKey, remoteHtlcPubkey, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc)
          val sig = Transactions.sign(tx, localHtlcPrivkey)
          Transactions.addSigs(tx, sig, preimage)
        })

        // (incoming htlc for which we don't have the preimage: nothing to do, it will timeout eventually and they will get their funds back)

        // outgoing htlc: they may or may not have the preimage, the only thing to do is try to get back our funds after timeout
        case DirectedHtlc(IN, add: UpdateAddHtlc) => generateTx("claim-htlc-timeout")(Try {
          val tx = Transactions.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, Satoshi(localParams.dustLimitSatoshis), localHtlcPrivkey.publicKey, remoteHtlcPubkey, remoteRevocationPubkey, localParams.defaultFinalScriptPubKey, add, feeratePerKwHtlc)
          val sig = Transactions.sign(tx, localHtlcPrivkey)
          Transactions.addSigs(tx, sig)
        })
      }.toSeq.flatten

      // OPTIONAL: let's check transactions are actually spendable
      //require(txes.forall(Transactions.checkSpendable(_).isSuccess), "the tx we produced are not spendable!")

      RemoteCommitPublished(
        commitTx = tx,
        claimMainOutputTx = mainTx.map(_.tx),
        claimHtlcSuccessTxs = txes.toList.collect { case c: ClaimHtlcSuccessTx => c.tx },
        claimHtlcTimeoutTxs = txes.toList.collect { case c: ClaimHtlcTimeoutTx => c.tx },
        irrevocablySpent = Map.empty
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
    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction)(implicit log: LoggingAdapter): Option[RevokedCommitPublished] = {
      import commitments._
      require(tx.txIn.size == 1, "commitment tx should have 1 input")
      val obscuredTxNumber = Transactions.decodeTxNumber(tx.txIn(0).sequence, tx.lockTime)
      // this tx has been published by remote, so we need to invert local/remote params
      val txnumber = Transactions.obscuredCommitTxNumber(obscuredTxNumber, !localParams.isFunder, remoteParams.paymentBasepoint, localParams.paymentBasepoint)
      require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
      log.warning(s"counterparty has published revoked commit txnumber=$txnumber")
      // now we know what commit number this tx is referring to, we can derive the commitment point from the shachain
      remotePerCommitmentSecrets.getHash(0xFFFFFFFFFFFFL - txnumber)
        .map(d => Scalar(d))
        .map { remotePerCommitmentSecret =>
          val remotePerCommitmentPoint = remotePerCommitmentSecret.toPoint

          val remoteDelayedPaymentPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
          val remoteRevocationPrivkey = Generators.revocationPrivKey(localParams.revocationSecret, remotePerCommitmentSecret)
          val localPrivkey = Generators.derivePrivKey(localParams.paymentKey, remotePerCommitmentPoint)

          // no need to use a high fee rate for our main output (we are the only one who can spend it)
          val feeratePerKwMain = Globals.feeratesPerKw.get.blocks_6
          // we need to use a high fee here for punishment txes because after a delay they can be spent by the counterparty
          val feeratePerKwPenalty = Globals.feeratesPerKw.get.block_1

          // first we will claim our main output right away
          val mainTx = generateTx("claim-p2wpkh-output")(Try {
            val claimMain = Transactions.makeClaimP2WPKHOutputTx(tx, Satoshi(localParams.dustLimitSatoshis), localPrivkey.publicKey, localParams.defaultFinalScriptPubKey, feeratePerKwMain)
            val sig = Transactions.sign(claimMain, localPrivkey)
            Transactions.addSigs(claimMain, localPrivkey.publicKey, sig)
          })

          // then we punish them by stealing their main output
          val mainPenaltyTx = generateTx("main-penalty")(Try {
            // TODO: we should use the current fee rate, not the initial fee rate that we get from localParams
            val txinfo = Transactions.makeMainPenaltyTx(tx, Satoshi(localParams.dustLimitSatoshis), remoteRevocationPrivkey.publicKey, localParams.defaultFinalScriptPubKey, localParams.toSelfDelay, remoteDelayedPaymentPubkey, feeratePerKwPenalty)
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
            htlcPenaltyTxs = Nil,
            irrevocablySpent = Map.empty
          )
        }
    }

    /**
      * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
      * local commit scenario and keep track of it.
      *
      * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
      * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
      * want to wait forever before declaring that the channel is CLOSED.
      *
      * @param localCommitPublished
      * @return
      */
    def updateLocalCommitPublished(localCommitPublished: LocalCommitPublished, tx: Transaction) = {
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = localCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = localCommitPublished.commitTx.txid == outPoint.txid
        // is the tx one of our 3rd stage delayed txes? (a 3rd stage tx is a tx spending the output of an htlc tx, which
        // is itself spending the output of the commitment tx)
        val is3rdStageDelayedTx = localCommitPublished.claimHtlcDelayedTx.map(_.txid).contains(outPoint.txid)
        isCommitTx || spendsTheCommitTx || is3rdStageDelayedTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      localCommitPublished.copy(irrevocablySpent = localCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => (o -> tx.txid)).toMap)
    }

    /**
      * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
      * remote commit scenario and keep track of it.
      *
      * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
      * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
      * want to wait forever before declaring that the channel is CLOSED.
      *
      * @param remoteCommitPublished
      * @return
      */
    def updateRemoteCommitPublished(remoteCommitPublished: RemoteCommitPublished, tx: Transaction) = {
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = remoteCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = remoteCommitPublished.commitTx.txid == outPoint.txid
        // TODO: we don't currently spend htlc transactions
        isCommitTx || spendsTheCommitTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      remoteCommitPublished.copy(irrevocablySpent = remoteCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => (o -> tx.txid)).toMap)
    }

    /**
      * In CLOSING state, when we are notified that a transaction has been confirmed, we check if this tx belongs in the
      * revoked commit scenario and keep track of it.
      *
      * We need to keep track of all transactions spending the outputs of the commitment tx, because some outputs can be
      * spent both by us and our counterparty. Because of that, some of our transactions may never confirm and we don't
      * want to wait forever before declaring that the channel is CLOSED.
      *
      * @param revokedCommitPublished
      * @return
      */
    def updateRevokedCommitPublished(revokedCommitPublished: RevokedCommitPublished, tx: Transaction) = {
      // even if our txes only have one input, maybe our counterparty uses a different scheme so we need to iterate
      // over all of them to check if they are relevant
      val relevantOutpoints = tx.txIn.map(_.outPoint).filter { outPoint =>
        // is this the commit tx itself ? (we could do this outside of the loop...)
        val isCommitTx = revokedCommitPublished.commitTx.txid == tx.txid
        // does the tx spend an output of the local commitment tx?
        val spendsTheCommitTx = revokedCommitPublished.commitTx.txid == outPoint.txid
        isCommitTx || spendsTheCommitTx
      }
      // then we add the relevant outpoints to the map keeping track of which txid spends which outpoint
      revokedCommitPublished.copy(irrevocablySpent = revokedCommitPublished.irrevocablySpent ++ relevantOutpoints.map(o => (o -> tx.txid)).toMap)
    }

    /**
      * A local commit is considered done when:
      * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
      * - all 3rd stage txes (txes spending htlc txes) have been confirmed
      *
      * @param localCommitPublished
      * @return
      */
    def isLocalCommitDone(localCommitPublished: LocalCommitPublished) = {
      // is the commitment tx buried? (we need to check this because we may not have nay outputs)
      val isCommitTxConfirmed = localCommitPublished.irrevocablySpent.values.toSet.contains(localCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx? we just subtract all known spent outputs from the ones we control
      val commitOutputsSpendableByUs = (localCommitPublished.claimMainDelayedOutputTx.toSeq ++ localCommitPublished.htlcSuccessTxs ++ localCommitPublished.htlcTimeoutTxs)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- localCommitPublished.irrevocablySpent.keys
      // which htlc delayed txes can we expect to be confirmed?
      val unconfirmedHtlcDelayedTxes = localCommitPublished.claimHtlcDelayedTx
        .filter(tx => (tx.txIn.map(_.outPoint.txid).toSet -- localCommitPublished.irrevocablySpent.values).isEmpty) // only the txes which parents are already confirmed may get confirmed (note that this also eliminates outputs that have been double-spent by a competing tx)
        .filterNot(tx => localCommitPublished.irrevocablySpent.values.toSet.contains(tx.txid)) // has the tx already been confirmed?
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty && unconfirmedHtlcDelayedTxes.isEmpty
    }

    /**
      * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
      * (even if the spending tx was not ours).
      *
      * @param remoteCommitPublished
      * @return
      */
    def isRemoteCommitDone(remoteCommitPublished: RemoteCommitPublished) = {
      // is the commitment tx buried? (we need to check this because we may not have nay outputs)
      val isCommitTxConfirmed = remoteCommitPublished.irrevocablySpent.values.toSet.contains(remoteCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx?
      val commitOutputsSpendableByUs = (remoteCommitPublished.claimMainOutputTx.toSeq ++ remoteCommitPublished.claimHtlcSuccessTxs ++ remoteCommitPublished.claimHtlcTimeoutTxs)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- remoteCommitPublished.irrevocablySpent.keys
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty
    }

    /**
      * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
      * (even if the spending tx was not ours).
      *
      * @param revokedCommitPublished
      * @return
      */
    def isRevokedCommitDone(revokedCommitPublished: RevokedCommitPublished) = {
      // is the commitment tx buried? (we need to check this because we may not have nay outputs)
      val isCommitTxConfirmed = revokedCommitPublished.irrevocablySpent.values.toSet.contains(revokedCommitPublished.commitTx.txid)
      // are there remaining spendable outputs from the commitment tx?
      val commitOutputsSpendableByUs = (revokedCommitPublished.claimMainOutputTx.toSeq ++ revokedCommitPublished.mainPenaltyTx)
        .flatMap(_.txIn.map(_.outPoint)).toSet -- revokedCommitPublished.irrevocablySpent.keys
      // TODO: we don't currently spend htlc transactions
      isCommitTxConfirmed && commitOutputsSpendableByUs.isEmpty
    }

    /**
      * This helper function tells if the utxo consumed by the given transaction has already been irrevocably spent (possibly by this very transaction)
      *
      * It can be useful to:
      *   - not attempt to publish this tx when we know this will fail
      *   - not watch for confirmations if we know the tx is already confirmed
      *   - not watch the corresponding utxo when we already know the final spending tx
      *
      * @param tx a tx with only one input
      * @param irrevocablySpent a map of known spent outpoints
      * @return true if we know for sure that the utxos consumed by the tx have already irrevocably been spent, false otherwise
      */
    def inputsAlreadySpent(tx: Transaction, irrevocablySpent: Map[OutPoint, BinaryData]): Boolean = {
      require(tx.txIn.size == 1, "only tx with one input is supported")
      val outPoint = tx.txIn.head.outPoint
      irrevocablySpent.contains(outPoint)
    }

  }

}
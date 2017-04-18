package fr.acinq.eclair.transactions

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering, MilliSatoshi, OutPoint, Protocol, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptFlags, ScriptWitness, Transaction, TxIn, TxOut, millisatoshi2satoshi}
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.UpdateAddHtlc

import scala.util.Try

/**
  * Created by PM on 15/12/2016.
  */
object Transactions {

  // @formatter:off
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: BinaryData)
  object InputInfo {
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: Seq[ScriptElt]) = new InputInfo(outPoint, txOut, Script.write(redeemScript))
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
  }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: BinaryData) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  // @formatter:on

  /**
    * When *local* *current* [[CommitTx]] is published:
    *   - [[ClaimDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
    *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *     - [[ClaimDelayedOutputTx]] spends [[HtlcSuccessTx]] after a delay
    *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *     - [[ClaimDelayedOutputTx]] spends [[HtlcTimeoutTx]] after a delay
    *
    * When *remote* *current* [[CommitTx]] is published:
    *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
    *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *
    * When *remote* *revoked* [[CommitTx]] is published:
    *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
    *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
    *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
    *     - [[HtlcPenaltyTx]] spends [[HtlcSuccessTx]] using the per-commitment secret
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by local or remote)
    *     - [[HtlcPenaltyTx]] spends [[HtlcTimeoutTx]] using the per-commitment secret
    */

  val commitWeight = 724
  val htlcTimeoutWeight = 663
  val htlcSuccessWeight = 703
  val claimP2WPKHOutputWeight = 437
  val claimHtlcDelayedWeight = 482
  val claimHtlcSuccessWeight = 570
  val claimHtlcTimeoutWeight = 544
  val mainPenaltyWeight = 483

  def weight2fee(feeratePerKw: Long, weight: Int) = Satoshi((feeratePerKw * weight) / 1000)

  def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[Htlc] = {
    val htlcTimeoutFee = weight2fee(spec.feeratePerKw, htlcTimeoutWeight)
    spec.htlcs
      .filter(_.direction == OUT)
      .filter(htlc => MilliSatoshi(htlc.add.amountMsat) >= (dustLimit + htlcTimeoutFee))
      .toSeq
  }

  def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[Htlc] = {
    val htlcSuccessFee = weight2fee(spec.feeratePerKw, htlcSuccessWeight)
    spec.htlcs
      .filter(_.direction == IN)
      .filter(htlc => MilliSatoshi(htlc.add.amountMsat) >= (dustLimit + htlcSuccessFee))
      .toSeq
  }

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec)
    val weight = commitWeight + 172 * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
    weight2fee(spec.feeratePerKw, weight)
  }

  /**
    *
    * @param commitTxNumber         commit tx number
    * @param isFunder               true if local node is funder
    * @param localPaymentBasePoint  local payment base point
    * @param remotePaymentBasePoint remote payment base point
    * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
    */
  def obscuredCommitTxNumber(commitTxNumber: Long, isFunder: Boolean, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
    // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
    val h = if (isFunder)
      Crypto.sha256(localPaymentBasePoint.toBin(true) ++ remotePaymentBasePoint.toBin(true))
    else
      Crypto.sha256(remotePaymentBasePoint.toBin(true) ++ localPaymentBasePoint.toBin(true))

    val blind = Protocol.uint64(h.takeRight(6).reverse ++ BinaryData("0x0000"), ByteOrder.LITTLE_ENDIAN)
    commitTxNumber ^ blind
  }

  /**
    *
    * @param commitTx               commit tx
    * @param isFunder               true if local node is funder
    * @param localPaymentBasePoint  local payment base point
    * @param remotePaymentBasePoint remote payment base point
    * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
    */
  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
    val blind = obscuredCommitTxNumber(0, isFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val obscured = decodeTxNumber(commitTx.txIn(0).sequence, commitTx.lockTime)
    obscured ^ blind
  }

  /**
    * This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
    *
    * @param txnumber
    * @return (sequence, locktime)
    */
  def encodeTxNumber(txnumber: Long) = {
    require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
    (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  }

  def decodeTxNumber(sequence: Long, locktime: Long) = ((sequence & 0xffffffL) << 24) + (locktime & 0xffffffL)

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point, localIsFunder: Boolean, localDustLimit: Satoshi, localPubKey: PublicKey, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, spec: CommitmentSpec): CommitTx = {

    val commitFee = commitTxFee(localDustLimit, spec)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = localIsFunder match {
      case true => (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)) - commitFee, millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)))
      case false => (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)), millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)) - commitFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalDelayedOutput_opt = if (toLocalAmount >= localDustLimit) Some(TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)))) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= localDustLimit) Some(TxOut(toRemoteAmount, pay2wpkh(remotePubkey))) else None

    val htlcOfferedOutputs = trimOfferedHtlcs(localDustLimit, spec)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcOffered(localPubKey, remotePubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash)))))
    val htlcReceivedOutputs = trimReceivedHtlcs(localDustLimit, spec)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcReceived(localPubKey, remotePubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry))))

    val txnumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txnumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = sequence) :: Nil,
      txOut = toLocalDelayedOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ htlcOfferedOutputs ++ htlcReceivedOutputs,
      lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def makeHtlcTimeoutTx(commitTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localPubKey: PublicKey, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, feeratePerKw: Long, htlc: UpdateAddHtlc): HtlcTimeoutTx = {
    val fee = weight2fee(feeratePerKw, htlcTimeoutWeight)
    val redeemScript = htlcOffered(localPubKey, remotePubkey, localRevocationPubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - fee, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey))) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeHtlcSuccessTx(commitTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localPubkey: PublicKey, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, feeratePerKw: Long, htlc: UpdateAddHtlc): HtlcSuccessTx = {
    val fee = weight2fee(feeratePerKw, htlcSuccessWeight)
    val redeemScript = htlcReceived(localPubkey, remotePubkey, localRevocationPubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - fee, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey))) :: Nil,
      lockTime = 0), htlc.paymentHash)
  }

  def makeHtlcTxs(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int, localPubkey: PublicKey, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, spec: CommitmentSpec): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val htlcTimeoutTxs = trimOfferedHtlcs(localDustLimit, spec)
      .map(htlc => makeHtlcTimeoutTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, localDelayedPubkey, remotePubkey, spec.feeratePerKw, htlc.add))
    val htlcSuccessTxs = trimReceivedHtlcs(localDustLimit, spec)
      .map(htlc => makeHtlcSuccessTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, localDelayedPubkey, remotePubkey, spec.feeratePerKw, htlc.add))
    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData, htlc: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcSuccessTx = {
    val fee = weight2fee(feeratePerKw, claimHtlcSuccessWeight)
    val redeemScript = htlcOffered(remotePubkey, localPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData, htlc: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcTimeoutTx = {
    val fee = weight2fee(feeratePerKw, claimHtlcTimeoutWeight)
    val redeemScript = htlcReceived(remotePubkey, localPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localPubkey: PublicKey, localFinalScriptPubKey: BinaryData, feeratePerKw: Long): ClaimP2WPKHOutputTx = {
    val fee = weight2fee(feeratePerKw, claimP2WPKHOutputWeight)
    val redeemScript = Script.pay2pkh(localPubkey)
    val pubkeyScript = write(pay2wpkh(localPubkey))
    val outputIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
    ClaimP2WPKHOutputTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, localFinalScriptPubKey: BinaryData, feeratePerKw: Long): ClaimDelayedOutputTx = {
    val fee = weight2fee(feeratePerKw, claimHtlcDelayedWeight)
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
    ClaimDelayedOutputTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, toLocalDelay) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeMainPenaltyTx(commitTx: Transaction, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData, toRemoteDelay: Int, remoteDelayedPubkey: PublicKey, feeratePerKw: Long): MainPenaltyTx = {
    val fee = weight2fee(feeratePerKw, mainPenaltyWeight)
    val redeemScript = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    MainPenaltyTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeHtlcPenaltyTx(commitTx: Transaction): HtlcPenaltyTx = ???

  /**
    * This generates a partial transaction that will be completed by bitcoind using a 'fundrawtransaction' rpc call.
    * Since bitcoind may add a change output, we return the pubkeyScript so that we can do a lookup afterwards.
    *
    * @param amount
    * @param localFundingPubkey
    * @param remoteFundingPubkey
    * @return (partialTx, pubkeyScript)
    */
  def makePartialFundingTx(amount: Satoshi, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey): (Transaction, BinaryData) = {
    val pubkeyScript = write(pay2wsh(Scripts.multiSig2of2(localFundingPubkey, remoteFundingPubkey)))
    (Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0), pubkeyScript)
  }

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData, localIsFunder: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
    require(spec.htlcs.size == 0, "there shouldn't be any pending htlcs")

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = localIsFunder match {
      case true => (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)) - closingFee, millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)))
      case false => (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)), millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)) - closingFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalOutput_opt = if (toLocalAmount >= dustLimit) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0)
    ClosingTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: BinaryData): Int = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: Seq[ScriptElt]): Int = findPubKeyScriptIndex(tx, write(pubkeyScript))

  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, amount: Satoshi, key: PrivateKey): BinaryData = {
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, key)
  }

  // when the amount is not specified, we used the legacy (pre-segwit) signature scheme
  // this is only used to spend the to-remote output of a commit tx, which is the only non-segwit output
  // that we use
  // TODO: change this if the decide to use P2WPKH in the to-remote output
  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, key: PrivateKey): BinaryData = {
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, Satoshi(0), SIGVERSION_BASE, key)
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey): BinaryData = {
    require(txinfo.tx.txIn.size == 1, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimMainDelayedRevokedTx: MainPenaltyTx, revocationSig: BinaryData): MainPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimMainDelayedRevokedTx.input.redeemScript)
    claimMainDelayedRevokedTx.copy(tx = claimMainDelayedRevokedTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: BinaryData, remoteSig: BinaryData, paymentPreimage: BinaryData): HtlcSuccessTx = {
    val witness = witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript)
    htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: BinaryData, remoteSig: BinaryData): HtlcTimeoutTx = {
    val witness = witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript)
    htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: BinaryData, paymentPreimage: BinaryData): ClaimHtlcSuccessTx = {
    val witness = witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
    claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: BinaryData): ClaimHtlcTimeoutTx = {
    val witness = witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
    claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPubkey: BinaryData, localSig: BinaryData): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(localSig, localPubkey))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: BinaryData): ClaimDelayedOutputTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] =
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.tx.txIn(0).outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkSig(txinfo: TransactionWithInputInfo, sig: BinaryData, pubKey: PublicKey): Boolean = {
    val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
    Crypto.verifySignature(data, sig, pubKey)
  }

}

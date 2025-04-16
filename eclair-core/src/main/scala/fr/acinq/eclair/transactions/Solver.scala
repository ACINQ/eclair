package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.SigHash.SIGHASH_DEFAULT
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.KotlinUtils._
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OutPoint, Satoshi, Script, ScriptElt, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.{ScriptTree, SigHash}
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.crypto.keymanager.{CommitmentPublicKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.RedeemInfo.{SegwitV0, TaprootKeyPath, TaprootScriptPath}
import fr.acinq.eclair.transactions.Scripts.Taproot.NUMS_POINT
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta}
import scodec.bits.ByteVector

sealed trait Solver[T <: SolverData, U <: TransactionWithInputInfo] {
  def redeemInfo: RedeemInfo

  def commitmentFormat: CommitmentFormat

  def publicKeyScript: Seq[ScriptElt] = redeemInfo.publicKeyScript

  def createSpendingTxs(parentTx: Transaction, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Seq[Either[TxGenerationSkipped, U]] = {
    parentTx.txOut.zipWithIndex
      .collect { case (txOut: TxOut, outputIndex) if txOut.publicKeyScript == Script.write(publicKeyScript) =>
        val input = InputInfo(OutPoint(parentTx, outputIndex), parentTx.txOut(outputIndex), redeemInfo)
        createSpendingTx(input, localDustLimit, finalScriptPubkey, feeratePerKw)
      }
  }

  def createSpendingTx(parentTx: Transaction, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, U] = {
    parentTx.txOut.zipWithIndex
      .collectFirst { case (txOut: TxOut, outputIndex) if txOut.publicKeyScript == Script.write(publicKeyScript) =>
        val input = InputInfo(OutPoint(parentTx, outputIndex), parentTx.txOut(outputIndex), redeemInfo)
        createSpendingTx(input, localDustLimit, finalScriptPubkey, feeratePerKw)
      }.getOrElse(Left(OutputNotFound))
  }

  def sign(privateKey: PrivateKey, unsignedTx: U, extraUtxos: Map[OutPoint, TxOut], owner: TxOwner = TxOwner.Local): ByteVector64 = {
    unsignedTx.sign(privateKey, owner, commitmentFormat, extraUtxos)
  }

  def sign(privateKey: PrivateKey, unsignedTx: U, extraUtxos: Map[OutPoint, TxOut], sighashType: Int): ByteVector64 = {
    unsignedTx.sign(privateKey, sighashType, extraUtxos)
  }

  def scriptWitness(data: T): ScriptWitness

  def addSig(spendingTx: U, data: T): U

  def createSpendingTx(inputInfo: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, U]
}

sealed trait SolverData

object SolverData {
  case class SingleSig(localSig: ByteVector64) extends SolverData

  case class HtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64) extends SolverData

  case class HtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, preimage: ByteVector32) extends SolverData

  case class ClaimHtlcSuccess(localSig: ByteVector64, preimage: ByteVector32) extends SolverData
}

object Solver {
  trait ToLocal extends Solver[SolverData.SingleSig, ClaimLocalDelayedOutputTx] {
    def toLocalDelay: CltvExpiryDelta

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimLocalDelayedOutputTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(ClaimLocalDelayedOutputTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        ClaimLocalDelayedOutputTx(input, tx1)
      }
    }

    override def addSig(spendingTx: ClaimLocalDelayedOutputTx, data: SolverData.SingleSig): ClaimLocalDelayedOutputTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ToLocalSegwitv0(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends ToLocal {
    override val redeemInfo: SegwitV0 = SegwitV0(toLocalDelayed(keys, toLocalDelay))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Scripts.witnessToLocalDelayedAfterDelay(data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class ToLocalTaproot(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends ToLocal {
    private val scriptTree: ScriptTree.Branch = Taproot.toLocalScriptTree(keys, toLocalDelay)

    override def redeemInfo: TaprootScriptPath = TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.getLeft.hash())

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(data.localSig)), scriptTree)
  }

  object ToLocal {
    def apply(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, ClaimLocalDelayedOutputTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ToLocalTaproot(keys, toLocalDelay, commitmentFormat)
      case _ => ToLocalSegwitv0(keys, toLocalDelay, commitmentFormat)
    }
  }

  trait ToAnchor extends Solver[SolverData.SingleSig, ClaimAnchorOutputTx] {
    def confirmationTarget: ConfirmationTarget

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimAnchorOutputTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
        txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
        lockTime = 0)
      Right(ClaimAnchorOutputTx(input, tx, confirmationTarget))
    }

    override def addSig(spendingTx: ClaimAnchorOutputTx, data: SolverData.SingleSig): ClaimAnchorOutputTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ToAnchorSegwitV0(anchorPubkey: PublicKey, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat) extends ToAnchor {
    override val redeemInfo: SegwitV0 = SegwitV0(anchor(anchorPubkey))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = witnessAnchor(data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class ToAnchorTaproot(anchorPubkey: PublicKey, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat) extends ToAnchor {
    override val redeemInfo: TaprootKeyPath = TaprootKeyPath(anchorPubkey.xOnly, Some(Taproot.anchorScriptTree))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessKeyPathPay2tr(data.localSig)
  }

  object ToAnchor {
    def apply(anchorPubkey: PublicKey, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, ClaimAnchorOutputTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ToAnchorTaproot(anchorPubkey, confirmationTarget, commitmentFormat)
      case _ => ToAnchorSegwitV0(anchorPubkey, confirmationTarget, commitmentFormat)
    }
  }

  trait ToRemote extends Solver[SolverData.SingleSig, ClaimRemoteDelayedOutputTx] {
    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimRemoteDelayedOutputTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(ClaimRemoteDelayedOutputTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        ClaimRemoteDelayedOutputTx(input, tx1)
      }
    }

    override def addSig(spendingTx: ClaimRemoteDelayedOutputTx, data: SolverData.SingleSig): ClaimRemoteDelayedOutputTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ToRemoteSegwitV0(keys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat) extends ToRemote {
    override val redeemInfo: SegwitV0 = commitmentFormat match {
      case DefaultCommitmentFormat => SegwitV0(Script.pay2pkh(keys.publicKeys.remotePaymentPublicKey))
      case _ => SegwitV0(toRemoteDelayed(keys.publicKeys))
    }

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = commitmentFormat match {
      case DefaultCommitmentFormat => ScriptWitness(Seq(der(data.localSig), keys.ourPaymentPublicKey.value))
      case _ => witnessClaimToRemoteDelayedFromCommitTx(data.localSig, Script.write(redeemInfo.redeemScript))
    }
  }

  private case class ToRemoteTaproot(keys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat) extends ToRemote {
    private val scriptTree: ScriptTree.Leaf = Taproot.toRemoteScriptTree(keys.publicKeys)
    override val redeemInfo: TaprootScriptPath = TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.hash())

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessScriptPathPay2tr(redeemInfo.internalKey, scriptTree, ScriptWitness(Seq(data.localSig)), scriptTree)
  }

  object ToRemote {
    def apply(keys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, ClaimRemoteDelayedOutputTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ToRemoteTaproot(keys, commitmentFormat)
      case _ => ToRemoteSegwitV0(keys, commitmentFormat)
    }
  }

  trait HtlcTimeout extends Solver[SolverData.HtlcTimeout, HtlcTimeoutTx] {
    override def addSig(spendingTx: HtlcTimeoutTx, data: SolverData.HtlcTimeout): HtlcTimeoutTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  case class HtlcTimeoutSegwitV0(keys: CommitmentPublicKeys, timeoutTx: HtlcTimeoutTx, commitmentFormat: CommitmentFormat) extends HtlcTimeout {
    val redeemInfo: RedeemInfo.SegwitV0 = SegwitV0(htlcOffered(keys, timeoutTx.paymentHash, commitmentFormat))

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcTimeoutTx] = Right(timeoutTx)

    override def scriptWitness(data: SolverData.HtlcTimeout): ScriptWitness = witnessHtlcTimeout(data.localSig, data.remoteSig, Script.write(redeemInfo.redeemScript), commitmentFormat)
  }

  case class HtlcTimeoutTaproot(keys: CommitmentPublicKeys, timeoutTx: HtlcTimeoutTx, commitmentFormat: CommitmentFormat) extends HtlcTimeout {
    val offeredHtlcTree: ScriptTree.Branch = Taproot.offeredHtlcScriptTree(keys, timeoutTx.paymentHash)
    val redeemInfo: RedeemInfo.TaprootScriptPath = TaprootScriptPath(keys.revocationPublicKey.xOnly, offeredHtlcTree, offeredHtlcTree.getLeft.hash())

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcTimeoutTx] = Right(timeoutTx)

    override def scriptWitness(data: SolverData.HtlcTimeout): ScriptWitness =
      Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(Taproot.encodeSig(data.remoteSig, SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY), Taproot.encodeSig(data.localSig, SIGHASH_DEFAULT))), redeemInfo.scriptTree)
  }

  object HtlcTimeout {
    def apply(keys: CommitmentPublicKeys, timeoutTx: HtlcTimeoutTx, commitmentFormat: CommitmentFormat): HtlcTimeout = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => HtlcTimeoutTaproot(keys, timeoutTx, commitmentFormat)
      case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => HtlcTimeoutSegwitV0(keys, timeoutTx, commitmentFormat)
    }
  }

  trait HtlcSuccess extends Solver[SolverData.HtlcSuccess, HtlcSuccessTx] {
    override def addSig(spendingTx: HtlcSuccessTx, data: SolverData.HtlcSuccess): HtlcSuccessTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  case class HtlcSuccessSegwitV0(keys: CommitmentPublicKeys, successTx: HtlcSuccessTx, commitmentFormat: CommitmentFormat) extends HtlcSuccess {
    val redeemInfo: RedeemInfo.SegwitV0 = SegwitV0(htlcReceived(keys, successTx.paymentHash, successTx.expiry, commitmentFormat))

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcSuccessTx] = Right(successTx)

    override def scriptWitness(data: SolverData.HtlcSuccess): ScriptWitness =
      witnessHtlcSuccess(data.localSig, data.remoteSig, data.preimage, Script.write(redeemInfo.redeemScript), commitmentFormat)
  }

  case class HtlcSuccessTaproot(keys: CommitmentPublicKeys, successTx: HtlcSuccessTx, commitmentFormat: CommitmentFormat) extends HtlcSuccess {
    val receivedHtlcTree: ScriptTree.Branch = Taproot.receivedHtlcScriptTree(keys, successTx.paymentHash, successTx.expiry)

    val redeemInfo: RedeemInfo.TaprootScriptPath = TaprootScriptPath(keys.revocationPublicKey.xOnly, receivedHtlcTree, receivedHtlcTree.getRight.hash())

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcSuccessTx] = Right(successTx)

    override def scriptWitness(data: SolverData.HtlcSuccess): ScriptWitness =
      Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(Taproot.encodeSig(data.remoteSig, SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY), Taproot.encodeSig(data.localSig, SIGHASH_DEFAULT), data.preimage)), redeemInfo.scriptTree)
  }

  object HtlcSuccess {
    def apply(keys: CommitmentPublicKeys, successTx: HtlcSuccessTx, commitmentFormat: CommitmentFormat): HtlcSuccess = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => HtlcSuccessTaproot(keys, successTx, commitmentFormat)
      case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => HtlcSuccessSegwitV0(keys, successTx, commitmentFormat)
    }
  }

  trait ClaimHtlcSuccess extends Solver[SolverData.ClaimHtlcSuccess, ClaimHtlcSuccessTx] {
    override def addSig(spendingTx: ClaimHtlcSuccessTx, data: SolverData.ClaimHtlcSuccess): ClaimHtlcSuccessTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ClaimHtlcSuccessSegwitV0(keys: RemoteCommitmentKeys, claimHtlcSuccessTx: ClaimHtlcSuccessTx, commitmentFormat: CommitmentFormat) extends ClaimHtlcSuccess {
    val redeemInfo: RedeemInfo.SegwitV0 = SegwitV0(htlcOffered(keys.publicKeys, claimHtlcSuccessTx.paymentHash, commitmentFormat))

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = Right(claimHtlcSuccessTx)

    override def scriptWitness(data: SolverData.ClaimHtlcSuccess): ScriptWitness =
      witnessClaimHtlcSuccessFromCommitTx(data.localSig, data.preimage, Script.write(redeemInfo.redeemScript))
  }

  private case class ClaimHtlcSuccessTaproot(keys: RemoteCommitmentKeys, claimHtlcSuccessTx: ClaimHtlcSuccessTx, commitmentFormat: CommitmentFormat) extends ClaimHtlcSuccess {
    private val offeredHtlcTree: ScriptTree.Branch = Taproot.offeredHtlcScriptTree(keys.publicKeys, claimHtlcSuccessTx.paymentHash)
    val redeemInfo: TaprootScriptPath = TaprootScriptPath(keys.revocationPublicKey.xOnly, offeredHtlcTree, offeredHtlcTree.getRight.hash())

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = Right(claimHtlcSuccessTx)

    override def scriptWitness(data: SolverData.ClaimHtlcSuccess): ScriptWitness =
      Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(data.localSig, data.preimage)), redeemInfo.scriptTree)
  }

  object ClaimHtlcSuccess {
    def apply(keys: RemoteCommitmentKeys, claimHtlcSuccessTx: ClaimHtlcSuccessTx, commitmentFormat: CommitmentFormat): ClaimHtlcSuccess = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ClaimHtlcSuccessTaproot(keys, claimHtlcSuccessTx, commitmentFormat)
      case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => ClaimHtlcSuccessSegwitV0(keys, claimHtlcSuccessTx, commitmentFormat)
    }
  }

  trait ClaimHtlcTimeout extends Solver[SolverData.SingleSig, ClaimHtlcTimeoutTx] {
    override def addSig(spendingTx: ClaimHtlcTimeoutTx, data: SolverData.SingleSig): ClaimHtlcTimeoutTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ClaimHtlcTimeoutSegwitV0(keys: RemoteCommitmentKeys, claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, commitmentFormat: CommitmentFormat) extends ClaimHtlcTimeout {
    val redeemInfo: RedeemInfo.SegwitV0 = SegwitV0(htlcReceived(keys.publicKeys, claimHtlcTimeoutTx.paymentHash, claimHtlcTimeoutTx.cltvExpiry, commitmentFormat))

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = Right(claimHtlcTimeoutTx)

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness =
      witnessClaimHtlcTimeoutFromCommitTx(data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class ClaimHtlcTimeoutTaproot(keys: RemoteCommitmentKeys, claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, commitmentFormat: CommitmentFormat) extends ClaimHtlcTimeout {
    private val offeredHtlcTree: ScriptTree.Branch = Taproot.receivedHtlcScriptTree(keys.publicKeys, claimHtlcTimeoutTx.paymentHash, claimHtlcTimeoutTx.cltvExpiry)
    val redeemInfo: TaprootScriptPath = TaprootScriptPath(keys.revocationPublicKey.xOnly, offeredHtlcTree, offeredHtlcTree.getLeft.hash())

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = Right(claimHtlcTimeoutTx)

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness =
      Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(data.localSig)), redeemInfo.scriptTree)
  }

  object ClaimHtlcTimeout {
    def apply(keys: RemoteCommitmentKeys, claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, commitmentFormat: CommitmentFormat): ClaimHtlcTimeout = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ClaimHtlcTimeoutTaproot(keys, claimHtlcTimeoutTx, commitmentFormat)
      case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat => ClaimHtlcTimeoutSegwitV0(keys, claimHtlcTimeoutTx, commitmentFormat)
    }
  }

  private case class HtlcDelayedSegwitV0(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends Solver[SolverData.SingleSig, HtlcDelayedTx] {
    private val solver: Solver[SolverData.SingleSig, ClaimLocalDelayedOutputTx] = Solver.ToLocal(keys, toLocalDelay, commitmentFormat)

    override def redeemInfo: RedeemInfo = solver.redeemInfo

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcDelayedTx] =
      solver.createSpendingTx(input, localDustLimit, finalScriptPubkey, feeratePerKw).map(c => HtlcDelayedTx(c.input, c.tx))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = solver.scriptWitness(data)

    override def addSig(spendingTx: HtlcDelayedTx, data: SolverData.SingleSig): HtlcDelayedTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class HtlcDelayedTaproot(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends Solver[SolverData.SingleSig, HtlcDelayedTx] {
    private val scriptTree: ScriptTree.Leaf = Taproot.htlcDelayedScriptTree(keys, toLocalDelay)

    override def redeemInfo: TaprootScriptPath = TaprootScriptPath(keys.revocationPublicKey.xOnly, scriptTree, scriptTree.hash())

    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcDelayedTx] = {
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(HtlcDelayedTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        HtlcDelayedTx(input, tx1)
      }
    }

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness =
      Script.witnessScriptPathPay2tr(redeemInfo.internalKey, scriptTree, ScriptWitness(Seq(data.localSig)), scriptTree)

    override def addSig(spendingTx: HtlcDelayedTx, data: SolverData.SingleSig): HtlcDelayedTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  object HtlcDelayed {
    def apply(keys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, HtlcDelayedTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => HtlcDelayedTaproot(keys, toLocalDelay, commitmentFormat)
      case _ => HtlcDelayedSegwitV0(keys, toLocalDelay, commitmentFormat)
    }
  }

  trait MainPenalty extends Solver[SolverData.SingleSig, MainPenaltyTx] {
    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, MainPenaltyTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(MainPenaltyTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        MainPenaltyTx(input, tx1)
      }
    }

    override def addSig(spendingTx: MainPenaltyTx, data: SolverData.SingleSig): MainPenaltyTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class MainPenaltySegwitV0(keys: CommitmentPublicKeys, toRemoteDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends MainPenalty {
    override val redeemInfo: SegwitV0 = SegwitV0(toLocalDelayed(keys, toRemoteDelay))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Scripts.witnessToLocalDelayedWithRevocationSig(data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class MainPenaltyTaproot(keys: CommitmentPublicKeys, toRemoteDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends MainPenalty {
    private val scriptTree: ScriptTree.Branch = Taproot.toLocalScriptTree(keys, toRemoteDelay)

    override val redeemInfo: TaprootScriptPath = TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.getRight.hash())

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(data.localSig)), scriptTree)
  }

  object MainPenalty {
    def apply(keys: CommitmentPublicKeys, toRemoteDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, MainPenaltyTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => MainPenaltyTaproot(keys, toRemoteDelay, commitmentFormat)
      case _ => MainPenaltySegwitV0(keys, toRemoteDelay, commitmentFormat)
    }
  }

  trait OfferedHtlcPenalty extends Solver[SolverData.SingleSig, HtlcPenaltyTx] {
    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(HtlcPenaltyTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        HtlcPenaltyTx(input, tx1)
      }
    }

    override def addSig(spendingTx: HtlcPenaltyTx, data: SolverData.SingleSig): HtlcPenaltyTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class OfferedHtlcPenaltySegwitV0(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat) extends OfferedHtlcPenalty {
    override val redeemInfo: SegwitV0 = SegwitV0(Scripts.htlcOffered(keys.publicKeys, paymentHash, commitmentFormat))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Scripts.witnessHtlcWithRevocationSig(keys, data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class OfferedHtlcPenaltySegwitTaproot(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat) extends OfferedHtlcPenalty {
    val redeemInfo: TaprootKeyPath = TaprootKeyPath(keys.revocationPublicKey.xOnly, Some(Taproot.offeredHtlcScriptTree(keys.publicKeys, paymentHash)))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessKeyPathPay2tr(data.localSig)
  }

  object OfferedHtlcPenalty {
    def apply(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, HtlcPenaltyTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => OfferedHtlcPenaltySegwitTaproot(keys, paymentHash, commitmentFormat)
      case _ => OfferedHtlcPenaltySegwitV0(keys, paymentHash, commitmentFormat)
    }
  }

  trait ReceivedHtlcPenalty extends Solver[SolverData.SingleSig, HtlcPenaltyTx] {
    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(HtlcPenaltyTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        HtlcPenaltyTx(input, tx1)
      }
    }

    override def addSig(spendingTx: HtlcPenaltyTx, data: SolverData.SingleSig): HtlcPenaltyTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ReceivedHtlcPenaltySegwitV0(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, lockTime: CltvExpiry, commitmentFormat: CommitmentFormat) extends ReceivedHtlcPenalty {
    override val redeemInfo: SegwitV0 = SegwitV0(Scripts.htlcReceived(keys.publicKeys, paymentHash, lockTime, commitmentFormat))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Scripts.witnessHtlcWithRevocationSig(keys, data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class ReceivedHtlcPenaltySegwitTaproot(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, lockTime: CltvExpiry, commitmentFormat: CommitmentFormat) extends ReceivedHtlcPenalty {
    val redeemInfo: TaprootKeyPath = TaprootKeyPath(keys.revocationPublicKey.xOnly, Some(Taproot.receivedHtlcScriptTree(keys.publicKeys, paymentHash, lockTime)))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessKeyPathPay2tr(data.localSig)
  }

  object ReceivedHtlcPenalty {
    def apply(keys: RemoteCommitmentKeys, paymentHash: ByteVector32, lockTime: CltvExpiry, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, HtlcPenaltyTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ReceivedHtlcPenaltySegwitTaproot(keys, paymentHash, lockTime, commitmentFormat)
      case _ => ReceivedHtlcPenaltySegwitV0(keys, paymentHash, lockTime, commitmentFormat)
    }
  }

  trait ClaimHtlcDelayedOutputPenalty extends Solver[SolverData.SingleSig, ClaimHtlcDelayedOutputPenaltyTx] {
    override def createSpendingTx(input: InputInfo, localDustLimit: Satoshi, finalScriptPubkey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx] = {
      // unsigned transaction
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(Satoshi(0), finalScriptPubkey) :: Nil,
        lockTime = 0)
      // compute weight with a dummy 73 bytes signature (the largest you can get)
      val dummySignedTx = addSig(ClaimHtlcDelayedOutputPenaltyTx(input, tx), SolverData.SingleSig(PlaceHolderSig))
      skipTxIfBelowDust(dummySignedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
        ClaimHtlcDelayedOutputPenaltyTx(input, tx1)
      }
    }

    override def addSig(spendingTx: ClaimHtlcDelayedOutputPenaltyTx, data: SolverData.SingleSig): ClaimHtlcDelayedOutputPenaltyTx =
      spendingTx.copy(tx = spendingTx.tx.updateWitness(0, scriptWitness(data)))
  }

  private case class ClaimHtlcDelayedOutputPenaltySegwitV0(keys: RemoteCommitmentKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends ClaimHtlcDelayedOutputPenalty {
    override val redeemInfo: SegwitV0 = SegwitV0(Scripts.toLocalDelayed(keys.publicKeys, toLocalDelay))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = witnessToLocalDelayedWithRevocationSig(data.localSig, Script.write(redeemInfo.redeemScript))
  }

  private case class ClaimHtlcDelayedOutputPenaltyTaproot(keys: RemoteCommitmentKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends ClaimHtlcDelayedOutputPenalty {
    override val redeemInfo: TaprootKeyPath = TaprootKeyPath(keys.revocationPublicKey.xOnly, Some(Taproot.htlcDelayedScriptTree(keys.publicKeys, toLocalDelay)))

    override def scriptWitness(data: SolverData.SingleSig): ScriptWitness = Script.witnessKeyPathPay2tr(data.localSig)
  }

  object ClaimHtlcDelayedOutputPenalty {
    def apply(keys: RemoteCommitmentKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): Solver[SolverData.SingleSig, ClaimHtlcDelayedOutputPenaltyTx] = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => ClaimHtlcDelayedOutputPenaltyTaproot(keys, toLocalDelay, commitmentFormat)
      case _ => ClaimHtlcDelayedOutputPenaltySegwitV0(keys, toLocalDelay, commitmentFormat)
    }
  }
}



package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.Commitments.PostRevocationAction
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

/**
 *
 * @param main                  current valid commitments, according to our view of the blockchain
 * @param rbfed                 alternative versions of the main commitments
 * @param remoteChannelData_opt peer backup
 */

case class MetaCommitments(main: Commitments,
                           rbfed: List[Commitments] = Nil,
                           remoteChannelData_opt: Option[ByteVector] = None) {

  def all: List[Commitments] = main +: rbfed

  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateAddHtlc)] = {
    main.sendAdd(cmd, currentHeight, feeConf)
      .map { case (commitments, add) => (this.copy(main = commitments), add) }
  }

  def receiveAdd(add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, MetaCommitments] = {
    main.receiveAdd(add, feeConf)
      .map { commitments => this.copy(main = commitments) }
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (MetaCommitments, UpdateFulfillHtlc)] = {
    main.sendFulfill(cmd)
      .map { case (commitments, fulfill) => (this.copy(main = commitments), fulfill) }
  }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    main.receiveFulfill(fulfill)
      .map { case (commitments, origin, add) => (this.copy(main = commitments), origin, add) }
  }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (MetaCommitments, HtlcFailureMessage)] = {
    main.sendFail(cmd, nodeSecret)
      .map { case (commitments, fail) => (this.copy(main = commitments), fail) }
  }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (MetaCommitments, UpdateFailMalformedHtlc)] = {
    main.sendFailMalformed(cmd)
      .map { case (commitments, fail) => (this.copy(main = commitments), fail) }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    main.receiveFail(fail)
      .map { case (commitments, origin, add) => (this.copy(main = commitments), origin, add) }
  }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    main.receiveFailMalformed(fail)
      .map { case (commitments, origin, add) => (this.copy(main = commitments), origin, add) }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateFee)] = {
    main.sendFee(cmd, feeConf)
      .map { case (commitments, fee) => (this.copy(main = commitments), fee) }
  }

  def receiveFee(fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, MetaCommitments] = {
    main.receiveFee(fee, feeConf)
      .map { commitments => this.copy(main = commitments) }
  }

  def sendCommit(keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, CommitSig)] = {
    main.sendCommit(keyManager)
      .map { case (commitments, sig) => (this.copy(main = commitments), sig) }
  }

  def receiveCommit(sig: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, RevokeAndAck)] = {
    main.receiveCommit(sig, keyManager)
      .map { case (commitments, rev) => (this.copy(main = commitments), rev) }
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (MetaCommitments, Seq[PostRevocationAction])] = {
    main.receiveRevocation(revocation, maxDustExposure)
      .map { case (commitments, actions) => (this.copy(main = commitments), actions) }
  }

  // TODO: Local/RemoteChanges should only be part of MetaCommitments
  def discardUnsignedUpdates(implicit log: LoggingAdapter): MetaCommitments = {
    this.copy(main = main.discardUnsignedUpdates)
  }

  def localHasChanges: Boolean = main.localHasChanges

}

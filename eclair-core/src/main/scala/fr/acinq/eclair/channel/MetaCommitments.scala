package fr.acinq.eclair.channel

import akka.event.LoggingAdapter
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.Commitments.PostRevocationAction
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.wire.protocol.CommitSigTlv.{AlternativeCommitSig, AlternativeCommitSigsTlv}
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

/**
 *
 * @param all                   all potentially valid commitments
 * @param remoteChannelData_opt peer backup
 */

case class MetaCommitments(all: List[Commitments],
                           remoteChannelData_opt: Option[ByteVector] = None) {

  require(all.nonEmpty, "there must be at least one commitments")

  /** current valid commitments, according to our view of the blockchain */
  val main: Commitments = all.head

  private def sequence[T](collection: List[Either[ChannelException, T]]): Either[ChannelException, List[T]] =
    collection.foldRight[Either[ChannelException, List[T]]](Right(Nil)) {
      case (Right(success), Right(res)) => Right(success +: res)
      case (Right(_), Left(res)) => Left(res)
      case (Left(failure), _) => Left(failure)
    }

  def sendAdd(cmd: CMD_ADD_HTLC, currentHeight: BlockHeight, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateAddHtlc)] = {
    sequence(all.map(_.sendAdd(cmd, currentHeight, feeConf)))
      .map { res: List[(Commitments, UpdateAddHtlc)] => (res.map(_._1), res.head._2) } // we only keep the first update_add_htlc (the others are duplicates)
      .map { case (commitments, add) => (this.copy(all = commitments), add) }
  }

  def receiveAdd(add: UpdateAddHtlc, feeConf: OnChainFeeConf): Either[ChannelException, MetaCommitments] = {
    sequence(all.map(_.receiveAdd(add, feeConf)))
      .map { commitments => this.copy(all = commitments) }
  }

  def sendFulfill(cmd: CMD_FULFILL_HTLC): Either[ChannelException, (MetaCommitments, UpdateFulfillHtlc)] = {
    sequence(all.map(_.sendFulfill(cmd)))
      .map { res: List[(Commitments, UpdateFulfillHtlc)] => (res.map(_._1), res.head._2) }
      .map { case (commitments, fulfill) => (this.copy(all = commitments), fulfill) }
  }

  def receiveFulfill(fulfill: UpdateFulfillHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFulfill(fulfill)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.map(_._1), res.head._2, res.head._3) }
      .map { case (commitments, origin, add) => (this.copy(all = commitments), origin, add) }
  }

  def sendFail(cmd: CMD_FAIL_HTLC, nodeSecret: PrivateKey): Either[ChannelException, (MetaCommitments, HtlcFailureMessage)] = {
    sequence(all.map(_.sendFail(cmd, nodeSecret)))
      .map { res: List[(Commitments, HtlcFailureMessage)] => (res.map(_._1), res.head._2) }
      .map { case (commitments, fail) => (this.copy(all = commitments), fail) }
  }

  def sendFailMalformed(cmd: CMD_FAIL_MALFORMED_HTLC): Either[ChannelException, (MetaCommitments, UpdateFailMalformedHtlc)] = {
    sequence(all.map(_.sendFailMalformed(cmd)))
      .map { res: List[(Commitments, UpdateFailMalformedHtlc)] => (res.map(_._1), res.head._2) }
      .map { case (commitments, fail) => (this.copy(all = commitments), fail) }
  }

  def receiveFail(fail: UpdateFailHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFail(fail)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.map(_._1), res.head._2, res.head._3) }
      .map { case (commitments, origin, fail) => (this.copy(all = commitments), origin, fail) }
  }

  def receiveFailMalformed(fail: UpdateFailMalformedHtlc): Either[ChannelException, (MetaCommitments, Origin, UpdateAddHtlc)] = {
    sequence(all.map(_.receiveFailMalformed(fail)))
      .map { res: List[(Commitments, Origin, UpdateAddHtlc)] => (res.map(_._1), res.head._2, res.head._3) }
      .map { case (commitments, origin, fail) => (this.copy(all = commitments), origin, fail) }
  }

  def sendFee(cmd: CMD_UPDATE_FEE, feeConf: OnChainFeeConf): Either[ChannelException, (MetaCommitments, UpdateFee)] = {
    sequence(all.map(_.sendFee(cmd, feeConf)))
      .map { res: List[(Commitments, UpdateFee)] => (res.map(_._1), res.head._2) }
      .map { case (commitments, fee) => (this.copy(all = commitments), fee) }
  }

  def receiveFee(fee: UpdateFee, feeConf: OnChainFeeConf)(implicit log: LoggingAdapter): Either[ChannelException, MetaCommitments] = {
    sequence(all.map(_.receiveFee(fee, feeConf)))
      .map { commitments => this.copy(all = commitments) }
  }

  /** We need to send signatures for each commitments. */
  def sendCommit(keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, CommitSig)] = {
    sequence(all.map(_.sendCommit(keyManager)))
      .map { res: List[(Commitments, CommitSig)] =>
        val tlv = AlternativeCommitSigsTlv(res.foldLeft(List.empty[AlternativeCommitSig]) {
          case (sigs, (commitments, commitSig)) => AlternativeCommitSig(commitments.fundingTxId, commitSig.signature, commitSig.htlcSignatures) +: sigs
        })
        // we set all commit_sigs as tlv of the first commit_sig (the first sigs will we duplicated)
        val commitSig = res.head._2.modify(_.tlvStream.records).usingIf(tlv.commitSigs.size > 1)(tlv +: _.toList)
        val commitments = res.map(_._1)
        (this.copy(all = commitments), commitSig)
      }
  }

  def receiveCommit(sig: CommitSig, keyManager: ChannelKeyManager)(implicit log: LoggingAdapter): Either[ChannelException, (MetaCommitments, RevokeAndAck)] = {
    val sigs: Map[ByteVector32, CommitSig] = sig.alternativeCommitSigs match {
      case Nil => Map(all.head.fundingTxId -> sig) // no alternative sigs: we use the commit_sig message as-is, we assume it is for the first commitments
      case alternativeCommitSig =>
        // if there are alternative sigs, then we expand the sigs to build n individual commit_sig that we will apply to the corresponding commitments
        alternativeCommitSig.map { altSig =>
          altSig.fundingTxId -> sig
            .modify(_.signature).setTo(altSig.signature)
            .modify(_.htlcSignatures).setTo(altSig.htlcSignatures)
            .modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[AlternativeCommitSigsTlv]))
        }.toMap
    }
    sequence(all.map(c => c.receiveCommit(sigs(c.fundingTxId), keyManager)))
      .map { res: List[(Commitments, RevokeAndAck)] => (res.map(_._1), res.head._2) }
      .map { case (commitments, rev) => (this.copy(all = commitments), rev) }
  }

  def receiveRevocation(revocation: RevokeAndAck, maxDustExposure: Satoshi): Either[ChannelException, (MetaCommitments, Seq[PostRevocationAction])] = {
    sequence(all.map(c => c.receiveRevocation(revocation, maxDustExposure)))
      .map { res: List[(Commitments, Seq[PostRevocationAction])] => (res.map(_._1), res.head._2) }
      .map { case (commitments, actions) => (this.copy(all = commitments), actions) }
  }

  // TODO: Local/RemoteChanges should only be part of MetaCommitments
  def discardUnsignedUpdates(implicit log: LoggingAdapter): MetaCommitments = {
    this.copy(all = all.map(_.discardUnsignedUpdates))
  }

  def localHasChanges: Boolean = main.localHasChanges

}

object MetaCommitments {
  def apply(commitments: Commitments): MetaCommitments = MetaCommitments(all = commitments +: Nil)
}
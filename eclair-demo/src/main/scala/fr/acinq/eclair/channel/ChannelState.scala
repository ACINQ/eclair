package fr.acinq.eclair.channel

import com.trueaccord.scalapb.GeneratedMessage
import fr.acinq.bitcoin.BinaryData
import lightning._

/**
  * Created by PM on 19/01/2016.
  */

// @formatter:off

sealed trait Direction
case object IN extends Direction
case object OUT extends Direction

final case class Change2(direction: Direction, ack: Long, msg: GeneratedMessage)

case class Htlc2(id: Long, amountMsat: Int, rHash: sha256_hash, expiry: locktime, nextNodeIds: Seq[String] = Nil, val previousChannelId: Option[BinaryData])

case class Htlc(direction: Direction, id: Long, amountMsat: Int, rHash: sha256_hash, expiry: locktime, nextNodeIds: Seq[String] = Nil, val previousChannelId: Option[BinaryData])

// @formatter:on

case class ChannelOneSide(pay_msat: Long, fee_msat: Long, htlcs_received: Seq[Htlc2]) {
  val funds = pay_msat + fee_msat
}

case class ChannelState(us: ChannelOneSide, them: ChannelOneSide) {
  /**
    * Because each party needs to be able to compute the other party's commitment tx
    *
    * @return the channel state as seen by the other party
    */
  def reverse: ChannelState = this.copy(them = us, us = them)

  def commit_changes(staged: List[Change2]): ChannelState = {
    staged.foldLeft(this) {
      case (state, Change2(direction, _, htlc: update_add_htlc)) => state.add_htlc(direction, Htlc2(htlc.id, htlc.amountMsat, htlc.rHash, htlc.expiry, Nil, None)) // TODO
      case (state, Change2(direction, _, fulfill: update_fulfill_htlc)) => state.fulfill_htlc(direction, fulfill.id, fulfill.r)
      case (state, Change2(direction, _, fail: update_fail_htlc)) => state.fail_htlc(direction, fail.id)
    }
  }

  def add_htlc(direction: Direction, htlc: Htlc2): ChannelState =
    direction match {
      case IN => this.copy(them = them.copy(pay_msat = them.pay_msat - htlc.amountMsat), us = us.copy(htlcs_received = us.htlcs_received :+ htlc))
      case OUT => this.copy(them = them.copy(htlcs_received = them.htlcs_received :+ htlc), us = us.copy(pay_msat = us.pay_msat - htlc.amountMsat))
    }

  def fulfill_htlc(direction: Direction, id: Long, r: sha256_hash): ChannelState =
    direction match {
      case IN =>
        them.htlcs_received.find(_.id == id) match {
          case Some(htlc) =>
            // we were the sender of this htlc
            val prev_fee = this.us.fee_msat + this.them.fee_msat
            val new_them_amount_nofee = them.pay_msat + htlc.amountMsat + them.fee_msat
            val new_them_fee = Math.min(prev_fee / 2, new_them_amount_nofee)
            val new_us_fee = prev_fee - new_them_fee
            val new_them_amount = new_them_amount_nofee - new_them_fee
            val new_us_amount = us.pay_msat + us.fee_msat - new_us_fee
            this.copy(
              us = us.copy(pay_msat = new_us_amount, fee_msat = new_us_fee),
              them = them.copy(pay_msat = new_them_amount, htlcs_received = them.htlcs_received.filterNot(_ == htlc), fee_msat = new_them_fee))
          case None => throw new RuntimeException(s"could not find htlc direction=$direction id=$id")
        }
      case OUT =>
        us.htlcs_received.find(_.id == id) match {
          case Some(htlc) =>
            // we were the receiver of this htlc
            val prev_fee = this.us.fee_msat + this.them.fee_msat
            val new_us_amount_nofee = us.pay_msat + htlc.amountMsat + us.fee_msat
            val new_us_fee = Math.min(prev_fee / 2, new_us_amount_nofee)
            val new_them_fee = prev_fee - new_us_fee
            val new_us_amount = new_us_amount_nofee - new_us_fee
            val new_them_amount = them.pay_msat + them.fee_msat - new_them_fee
            this.copy(
              us = us.copy(pay_msat = new_us_amount, htlcs_received = us.htlcs_received.filterNot(_ == htlc), fee_msat = new_us_fee),
              them = them.copy(pay_msat = new_them_amount, fee_msat = new_them_fee))
          case None => throw new RuntimeException(s"could not find htlc direction=$direction id=$id")
        }
    }

  /**
    * Cause of failures include: timeout, routing failure
    *
    * @param id
    * @return
    */
  def fail_htlc(direction: Direction, id: Long): ChannelState =
    direction match {
      case IN =>
        them.htlcs_received.find(_.id == id) match {
          case Some(htlc) => this.copy(them = them.copy(htlcs_received = them.htlcs_received.filterNot(_ == htlc)), us = us.copy(pay_msat = us.pay_msat + htlc.amountMsat))
          case None => throw new RuntimeException(s"could not find htlc direction=$direction id=$id")
        }
      case OUT =>
        us.htlcs_received.find(_.id == id) match {
          case Some(htlc) => this.copy(them = them.copy(pay_msat = them.pay_msat + htlc.amountMsat), us = us.copy(htlcs_received = us.htlcs_received.filterNot(_ == htlc)))
          case None => throw new RuntimeException(s"could not find htlc direction=$direction id=$id")
        }
    }

  def adjust_fees(fee: Long, is_funder: Boolean): ChannelState = {
    if (is_funder) {
      val (funder, nonfunder) = ChannelState.adjust_fees(this.us, this.them, fee)
      this.copy(us = funder, them = nonfunder)
    } else {
      val (funder, nonfunder) = ChannelState.adjust_fees(this.them, this.us, fee)
      this.copy(us = nonfunder, them = funder)
    }
  }

  def prettyString(): String = s"pay_us=${us.pay_msat} htlcs_us=${us.htlcs_received.map(_.amountMsat).sum} pay_them=${them.pay_msat} htlcs_them=${them.htlcs_received.map(_.amountMsat).sum} total=${us.pay_msat + us.htlcs_received.map(_.amountMsat).sum + them.pay_msat + them.htlcs_received.map(_.amountMsat).sum}"
}

object ChannelState {
  def adjust_fees(funder: ChannelOneSide, nonfunder: ChannelOneSide, fee: Long): (ChannelOneSide, ChannelOneSide) = {
    val nonfunder_fee = Math.min(fee - fee / 2, nonfunder.funds)
    val funder_fee = fee - nonfunder_fee
    (funder.copy(pay_msat = funder.funds - funder_fee, fee_msat = funder_fee), nonfunder.copy(pay_msat = nonfunder.funds - nonfunder_fee, fee_msat = nonfunder_fee))
  }
}
package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair._
import lightning.{sha256_hash, update_add_htlc}

/**
  * Created by PM on 19/01/2016.
  */
case class ChannelOneSide(pay_msat: Long, fee_msat: Long, htlcs: Seq[update_add_htlc])

case class ChannelState(us: ChannelOneSide, them: ChannelOneSide) {
  /**
    * Because each party needs to be able to compute the other party's commitment tx
    *
    * @return the channel state as seen by the other party
    */
  def reverse: ChannelState = this.copy(them = us, us = them)

  /**
    * Update the channel
    *
    * @param delta as seen by us, if delta > 0 we increase our balance
    * @return the update channel state
    */
  def update(delta: Long): ChannelState = this.copy(them = them.copy(pay_msat = them.pay_msat - delta), us = us.copy(pay_msat = us.pay_msat + delta))

  /**
    * Update our state when we send an htlc
    *
    * @param htlc
    * @return
    */
  def htlc_receive(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(pay_msat = them.pay_msat - htlc.amountMsat), us = us.copy(htlcs = us.htlcs :+ htlc))

  /**
    * Update our state when we receive an htlc
    *
    * @param htlc
    * @return
    */
  def htlc_send(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(htlcs = them.htlcs :+ htlc), us = us.copy(pay_msat = us.pay_msat - htlc.amountMsat))

  /**
    * We remove an existing htlc (can be because of a timeout, or a routing failure)
    *
    * @param rHash
    * @return
    */
  def htlc_remove(rHash: sha256_hash): ChannelState = {
    if (us.htlcs.find(_.rHash == rHash).isDefined) {
      // TODO not optimized
      val htlc = us.htlcs.find(_.rHash == rHash).get
      // we were the receiver of this htlc
      this.copy(them = them.copy(pay_msat = them.pay_msat + htlc.amountMsat), us = us.copy(htlcs = us.htlcs.filterNot(_ == htlc)))
    } else if (them.htlcs.find(_.rHash == rHash).isDefined) {
      // TODO not optimized
      val htlc = them.htlcs.find(_.rHash == rHash).get
      // we were the sender of this htlc
      this.copy(them = them.copy(htlcs = them.htlcs.filterNot(_ == htlc)), us = us.copy(pay_msat = us.pay_msat + htlc.amountMsat))
    } else throw new RuntimeException(s"could not find corresponding htlc (rHash=$rHash)")
  }

  def htlc_fulfill(r: sha256_hash): ChannelState = {
    if (us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the receiver of this htlc
      val prev_fee = this.us.fee_msat + this.them.fee_msat
      val new_us_amount_nofee = us.pay_msat + htlc.amountMsat + us.fee_msat
      val new_us_fee = Math.min(prev_fee / 2, new_us_amount_nofee)
      val new_them_fee = prev_fee - new_us_fee
      val new_us_amount = new_us_amount_nofee - new_us_fee
      val new_them_amount = them.pay_msat + them.fee_msat - new_them_fee
      this.copy(
        us = us.copy(pay_msat = new_us_amount, htlcs = us.htlcs.filterNot(_ == htlc), fee_msat = new_us_fee),
        them = them.copy(pay_msat = new_them_amount, fee_msat = new_them_fee))
    } else if (them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the sender of this htlc
      val prev_fee = this.us.fee_msat + this.them.fee_msat
      val new_them_amount_nofee = them.pay_msat + htlc.amountMsat + them.fee_msat
      val new_them_fee = Math.min(prev_fee / 2, new_them_amount_nofee)
      val new_us_fee = prev_fee - new_them_fee
      val new_them_amount = new_them_amount_nofee - new_them_fee
      val new_us_amount = us.pay_msat + us.fee_msat - new_us_fee
      this.copy(
        us = us.copy(pay_msat = new_us_amount, fee_msat = new_us_fee),
        them = them.copy(pay_msat = new_them_amount, htlcs = them.htlcs.filterNot(_ == htlc), fee_msat = new_them_fee))
    } else throw new RuntimeException(s"could not find corresponding htlc (r=$r)")
  }

  def prettyString(): String = s"pay_us=${us.pay_msat} htlcs_us=${us.htlcs.map(_.amountMsat).sum} pay_them=${them.pay_msat} htlcs_them=${them.htlcs.map(_.amountMsat).sum} total=${us.pay_msat + us.htlcs.map(_.amountMsat).sum + them.pay_msat + them.htlcs.map(_.amountMsat).sum}"
}

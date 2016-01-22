package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair._
import lightning.{sha256_hash, update_add_htlc}

/**
  * Created by PM on 19/01/2016.
  */
case class ChannelOneSide(pay: Long, fee: Long, htlcs: Seq[update_add_htlc])

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
  def update(delta: Long): ChannelState = this.copy(them = them.copy(pay = them.pay - delta), us = us.copy(pay = us.pay + delta))

  /**
    * Update our state when we send an htlc
    *
    * @param htlc
    * @return
    */
  def htlc_receive(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(pay = them.pay - htlc.amountMsat), us = us.copy(htlcs = us.htlcs :+ htlc))

  /**
    * Update our state when we receive an htlc
    *
    * @param htlc
    * @return
    */
  def htlc_send(htlc: update_add_htlc): ChannelState = this.copy(them = them.copy(htlcs = them.htlcs :+ htlc), us = us.copy(pay = us.pay - htlc.amountMsat))

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
      this.copy(them = them.copy(pay = them.pay + htlc.amountMsat), us = us.copy(htlcs = us.htlcs.filterNot(_ == htlc)))
    } else if (them.htlcs.find(_.rHash == rHash).isDefined) {
      // TODO not optimized
      val htlc = them.htlcs.find(_.rHash == rHash).get
      // we were the sender of this htlc
      this.copy(them = them.copy(htlcs = them.htlcs.filterNot(_ == htlc)), us = us.copy(pay = us.pay + htlc.amountMsat))
    } else throw new RuntimeException(s"could not find corresponding htlc (rHash=$rHash)")
  }

  def htlc_fulfill(r: sha256_hash): ChannelState = {
    if (us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the receiver of this htlc
      this.copy(us = us.copy(pay = us.pay + htlc.amountMsat, htlcs = us.htlcs.filterNot(_ == htlc)))
    } else if (them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).isDefined) {
      // TODO not optimized
      val htlc = them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).get
      // we were the sender of this htlc
      this.copy(them = them.copy(pay = them.pay + htlc.amountMsat, htlcs = them.htlcs.filterNot(_ == htlc)))
    } else throw new RuntimeException(s"could not find corresponding htlc (r=$r)")
  }

  def prettyString(): String = s"pay_us=${us.pay} htlcs_us=${us.htlcs.map(_.amountMsat).sum} pay_them=${them.pay} htlcs_them=${them.htlcs.map(_.amountMsat).sum} total=${us.pay + us.htlcs.map(_.amountMsat).sum + them.pay + them.htlcs.map(_.amountMsat).sum}"
}

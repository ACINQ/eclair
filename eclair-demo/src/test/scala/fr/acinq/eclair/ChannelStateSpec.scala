package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.channel.{ChannelOneSide, ChannelState, Htlc}
import lightning.locktime.Locktime.Blocks
import lightning.{locktime, sha256_hash, update_add_htlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 26/01/2016.
  */
@RunWith(classOf[JUnitRunner])
class ChannelStateSpec extends FunSuite {

  test("fee management send") {
    val state_0 = ChannelState(
      us = ChannelOneSide(pay_msat = 950000000, fee_msat = 50000000, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq())
    )

    val r = sha256_hash(7, 7, 7, 7)
    val rHash = Crypto.sha256(r)
    val htlc = Htlc(100000000, rHash, locktime(Blocks(1)), Nil, None)
    val state_1 = state_0.htlc_send(htlc)
    assert(state_1 === ChannelState(
      us = ChannelOneSide(pay_msat = 850000000, fee_msat = 50000000, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq(htlc))
    ))

    val state_2 = state_1.htlc_fulfill(r)
    assert(state_2 === ChannelState(
      us = ChannelOneSide(pay_msat = 875000000, fee_msat = 25000000, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 75000000, fee_msat = 25000000, htlcs_received = Seq())
    ))
  }

  test("fee management receive") {
    val state_0 = ChannelState(
      us = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 950000000, fee_msat = 50000000, htlcs_received = Seq())
    )

    val r = sha256_hash(7, 7, 7, 7)
    val rHash = Crypto.sha256(r)
    val htlc = Htlc(2000000, rHash, locktime(Blocks(1)), Nil, None)
    val state_1 = state_0.htlc_receive(htlc)
    assert(state_1 === ChannelState(
      us = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq(htlc)),
      them = ChannelOneSide(pay_msat = 948000000, fee_msat = 50000000, htlcs_received = Seq())
    ))

    val state_2 = state_1.htlc_fulfill(r)
    assert(state_2 === ChannelState(
      us = ChannelOneSide(pay_msat = 0, fee_msat = 2000000, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 950000000, fee_msat = 48000000, htlcs_received = Seq())
    ))
  }

  test("adjust fees") {
    val state_0 = ChannelState(
      us = ChannelOneSide(pay_msat = 950000*1000, fee_msat = 49900*1000, htlcs_received = Seq()),
      them = ChannelOneSide(pay_msat = 0, fee_msat = 100*1000, htlcs_received = Seq())
    )
    val state_1 = state_0.adjust_fees(100000, true)
    println(state_1)
  }
}

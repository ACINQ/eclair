package fr.acinq.eclair

import fr.acinq.bitcoin.{Crypto, Hash}
import fr.acinq.eclair.channel._
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

  test("basic fee computation") {
    // from https://github.com/rustyrussell/lightning-rfc/blob/master/bolts/02-wire-protocol.md
    assert(ChannelState.computeFee(1112, 2) === 446)
  }

  test("initial funding") {
    val feeRate = 200000
    val state = ChannelState.initialFunding(995940, feeRate)
    assert(state.us.pay_msat === 928340000 && state.us.fee_msat === 67600000)
  }

  test("fee management send") {
    val feeRate = 200000
    val state = ChannelState.initialFunding(995940, feeRate)

//    val state1 = state.add_htlc(OUT, Htlc(0, 1000000, Hash.Zeroes, locktime(Blocks(1)), Nil, None))
//    assert(state1.us.pay_msat === 920940000 && state1.us.fee_msat === 74000000)
//
//    val state_0 = ChannelState(
//      us = ChannelOneSide(pay_msat = 950000000, fee_msat = 50000000, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq())
//    )
//
//    val r = sha256_hash(7, 7, 7, 7)
//    val rHash = Crypto.sha256(r)
//    val htlc = Htlc2(0, 100000000, rHash, locktime(Blocks(1)), Nil, None)
//    val state_1 = state_0.add_htlc(OUT, htlc)
//    assert(state_1 === ChannelState(
//      us = ChannelOneSide(pay_msat = 850000000, fee_msat = 50000000, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq(htlc))
//    ))
//
//    val state_2 = state_1.fulfill_htlc(IN, htlc.id, r)
//    assert(state_2 === ChannelState(
//      us = ChannelOneSide(pay_msat = 875000000, fee_msat = 25000000, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 75000000, fee_msat = 25000000, htlcs_received = Seq())
//    ))
  }

//  test("fee management receive") {
//    val state_0 = ChannelState(
//      us = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 950000000, fee_msat = 50000000, htlcs_received = Seq())
//    )
//
//    val r = sha256_hash(7, 7, 7, 7)
//    val rHash = Crypto.sha256(r)
//    val htlc = Htlc2(0, 2000000, rHash, locktime(Blocks(1)), Nil, None)
//    val state_1 = state_0.add_htlc(IN, htlc)
//    assert(state_1 === ChannelState(
//      us = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs_received = Seq(htlc)),
//      them = ChannelOneSide(pay_msat = 948000000, fee_msat = 50000000, htlcs_received = Seq())
//    ))
//
//    val state_2 = state_1.fulfill_htlc(OUT, htlc.id, r)
//    assert(state_2 === ChannelState(
//      us = ChannelOneSide(pay_msat = 0, fee_msat = 2000000, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 950000000, fee_msat = 48000000, htlcs_received = Seq())
//    ))
//  }
//
//  test("adjust fees") {
//    val state_0 = ChannelState(
//      us = ChannelOneSide(pay_msat = 950000*1000, fee_msat = 49900*1000, htlcs_received = Seq()),
//      them = ChannelOneSide(pay_msat = 0, fee_msat = 100*1000, htlcs_received = Seq())
//    )
//    val state_1 = state_0.adjust_fees(100000, true)
//    println(state_1)
//  }
}

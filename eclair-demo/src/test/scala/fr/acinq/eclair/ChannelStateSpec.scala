package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.channel.{ChannelOneSide, ChannelState}
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

  test("fee management") {
    val state_0 = ChannelState(
      us = ChannelOneSide(pay_msat = 950000000, fee_msat = 50000000, htlcs = Seq()),
      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs = Seq())
    )

    val r = sha256_hash(7, 7, 7, 7)
    val rHash = Crypto.sha256(r)
    val htlc = update_add_htlc(rHash, 100000000, rHash, locktime(Blocks(1)))
    val state_1 = state_0.htlc_send(htlc)
    assert(state_1 === ChannelState(
      us = ChannelOneSide(pay_msat = 850000000, fee_msat = 50000000, htlcs = Seq()),
      them = ChannelOneSide(pay_msat = 0, fee_msat = 0, htlcs = Seq(htlc))
    ))

    val state_2 = state_1.htlc_fulfill(r)
    assert(state_2 === ChannelState(
      us = ChannelOneSide(pay_msat = 875000000, fee_msat = 25000000, htlcs = Seq()),
      them = ChannelOneSide(pay_msat = 75000000, fee_msat = 25000000, htlcs = Seq())
    ))
  }

}

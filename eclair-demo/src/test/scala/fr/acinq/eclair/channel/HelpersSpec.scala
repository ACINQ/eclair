package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair._
import lightning._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HelpersSpec extends FunSuite {
  test("add, fulfill and fail htlcs") {
    val spec = CommitmentSpec(Set(), 1000, 2000 * 1000, 0)
    val R1: sha256_hash = Crypto.sha256("foo".getBytes())
    val H1: sha256_hash = Crypto.sha256(R1)
    val R2: sha256_hash = Crypto.sha256("bar".getBytes())
    val H2: sha256_hash = Crypto.sha256(R2)

    val u1 = update_add_htlc(1, 1000, H1, locktime.defaultInstance, routing.defaultInstance)
    val spec1 = Helpers.reduce(spec, List(u1), Nil)
    assert(spec1.htlcs.size == 1 && spec1.htlcs.head.id == 1 && spec1.htlcs.head.rHash == H1)
    assert(spec1.amount_us_msat == spec.amount_us_msat - u1.amountMsat)
    assert(spec1.amount_them_msat == spec.amount_them_msat)
    assert(spec1.totalFunds == spec.totalFunds)

    val spec2 = Helpers.reduce(spec1, Nil, List(update_fulfill_htlc(u1.id, R1)))
    assert(spec2.htlcs.isEmpty && spec2.amount_them_msat == 1000 && spec2.totalFunds == spec.totalFunds)

    val u2 = update_add_htlc(2, 1000, H2, locktime.defaultInstance, routing.defaultInstance)
    val spec3 = Helpers.reduce(spec2, Nil, List(u2))
    assert(spec3.htlcs.size == 1)
    assert(spec3.amount_us_msat == spec2.amount_us_msat)
    assert(spec3.amount_them_msat == spec2.amount_them_msat - u2.amountMsat)
    assert(spec3.totalFunds == spec.totalFunds)

    val spec4 = Helpers.reduce(spec3, List(update_fail_htlc(u2.id, fail_reason.defaultInstance)), Nil)
    assert(spec4 == spec2)
  }
}

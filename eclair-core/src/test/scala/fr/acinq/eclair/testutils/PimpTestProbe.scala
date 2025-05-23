package fr.acinq.eclair.testutils

import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{OutPoint, Satoshi, TxId}
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.AvailableBalanceChanged
import org.scalatest.Assertions

import scala.reflect.ClassTag

case class PimpTestProbe(probe: TestProbe) extends Assertions {

  /**
   * Generic method to perform validation on an expected message.
   *
   * @param asserts should contains asserts on the message
   */
  def expectMsgTypeHaving[T](asserts: T => Unit)(implicit t: ClassTag[T]): T = {
    val msg = probe.expectMsgType[T]
    asserts(msg)
    msg
  }

  def expectWatchFundingSpent(txid: TxId, hints_opt: Option[Set[TxId]] = None): WatchFundingSpent =
    expectMsgTypeHaving[WatchFundingSpent](w => {
      assert(w.txId == txid, "txid")
      hints_opt.foreach(hints => assert(hints == w.hints))
    })

  def expectWatchFundingConfirmed(txid: TxId): WatchFundingConfirmed =
    expectMsgTypeHaving[WatchFundingConfirmed](w => assert(w.txId == txid, "txid"))

  def expectWatchOutputSpent(outpoint: OutPoint): WatchOutputSpent =
    expectMsgTypeHaving[WatchOutputSpent](w => assert(OutPoint(w.txId, w.outputIndex.toLong) == outpoint, "outpoint"))

  def expectWatchOutputsSpent(outpoints: Seq[OutPoint]): Seq[WatchOutputSpent] = {
    val watches = outpoints.map(_ => probe.expectMsgType[WatchOutputSpent])
    val watched = watches.map(w => OutPoint(w.txId, w.outputIndex.toLong))
    assert(watched.toSet == outpoints.toSet)
    watches
  }

  def expectWatchTxConfirmed(txid: TxId): WatchTxConfirmed =
    expectMsgTypeHaving[WatchTxConfirmed](w => assert(w.txId == txid, "txid"))

  def expectWatchTxConfirmed(txid: TxId, parentTxId: TxId): WatchTxConfirmed =
    expectMsgTypeHaving[WatchTxConfirmed](w => {
      assert(w.txId == txid, "txid")
      assert(w.delay_opt.map(_.parentTxId).contains(parentTxId))
    })

  def expectWatchPublished(txid: TxId): WatchPublished =
    expectMsgTypeHaving[WatchPublished](w => assert(w.txId == txid, "txid"))

  def expectAvailableBalanceChanged(balance: MilliSatoshi, capacity: Satoshi): AvailableBalanceChanged =
    expectMsgTypeHaving[AvailableBalanceChanged] { e =>
      // NB: we check raw local balance, not availableBalanceForSend, because the latter is more difficult to compute
      assert(e.commitments.active.map(_.localCommit.spec.toLocal).min == balance, "balance")
      assert(e.commitments.active.map(_.capacity).min == capacity, "capacity")
    }
}

object PimpTestProbe {

  implicit def convert(probe: TestProbe): PimpTestProbe = PimpTestProbe(probe)

}

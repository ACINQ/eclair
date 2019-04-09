package fr.acinq.eclair

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.io.Peer.OpenChannel
import org.scalatest.FunSuiteLike
import scodec.bits._

class EclairImplSpec extends TestKit(ActorSystem("mySystem")) with FunSuiteLike {
  test("convert fee rate properly") {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val paymentInitiator = TestProbe()
    val server = TestProbe()
    val kit = Kit(
      TestConstants.Alice.nodeParams,
      system,
      watcher.ref,
      paymentHandler.ref,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      paymentInitiator.ref,
      server.ref,
      new TestWallet()
    )
    val eclair = new EclairImpl(kit)
    val nodeId = PublicKey(hex"030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87")

    // standard conversion
    eclair.open(nodeId, fundingSatoshis = 10000000L, pushMsat = None, fundingFeerateSatByte = Some(5), flags = None)
    val open = switchboard.expectMsgType[OpenChannel]
    assert(open.fundingTxFeeratePerKw_opt == Some(1250))

    // check that minimum fee rate of 253 sat/bw is used
    eclair.open(nodeId, fundingSatoshis = 10000000L, pushMsat = None, fundingFeerateSatByte = Some(1), flags = None)
    val open1 = switchboard.expectMsgType[OpenChannel]
    assert(open1.fundingTxFeeratePerKw_opt == Some(MinimumFeeratePerKw))
  }
}

package fr.acinq.eclair.integration

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.Setup
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuite}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.sys.process._
import akka.pattern.ask
import akka.util.Timeout

/**
  * Created by PM on 15/03/2017.
  */
@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends FunSuite with BeforeAndAfterAll {

  val PATH_BITCOIND = "target/test-classes/integration/bitcoind/bitcoind.exe"
  val PATH_BITCOIND_DATADIR = "target/test-classes/integration/bitcoind/datadir"
  val PATH_ECLAIR_DATADIR_A = "target/test-classes/integration/datadir_A"
  val PATH_ECLAIR_DATADIR_B = "target/test-classes/integration/datadir_B"

  var bitcoind: Process = null
  var setupA: Setup = null
  var setupB: Setup = null

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = Timeout(30 seconds)

  override def beforeAll(): Unit = {
    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    setupA = new Setup(PATH_ECLAIR_DATADIR_A)
    setupB = new Setup(PATH_ECLAIR_DATADIR_B)
    setupA.boostrap
    setupB.boostrap
    generate(setupA.bitcoin_client.client, 300)
  }

  def generate(client: BitcoinJsonRPCClient, nBlocks: Int) =
    Await.result(client.invoke("generate", nBlocks), 10 seconds)

  override def afterAll(): Unit = {
    bitcoind.destroy()
  }

  test("connect A to B") {
    println(Await.result((setupA.switchboard ? NewConnection(
      remoteNodeId = setupB.nodeParams.privateKey.publicKey,
      address = setupB.nodeParams.address,
      newChannel_opt = Some(NewChannel(Satoshi(10000000), MilliSatoshi(200000000))))).mapTo[String], 10 seconds))
    Thread.sleep(10000)
    generate(setupA.bitcoin_client.client, 6)
    Thread.sleep(10000)
  }
}

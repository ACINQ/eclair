package fr.acinq.eclair.interop

import java.io.File
import java.nio.file.{Files, Paths}

import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, BitcoinJsonRPCClient}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel.Register.ListChannels
import fr.acinq.eclair.channel.{CLOSED, CLOSING, CMD_ADD_HTLC, _}
import fr.acinq.eclair.io.Server
import lightning.locktime
import lightning.locktime.Locktime.Blocks
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods._
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Tag}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.sys.process._

object InteropTest extends Tag("fr.acinq.eclair.tags.InteropTest")

object LinuxOnlyTest extends Tag("fr.acinq.eclair.tags.LinuxOnlyTest")

/**
  * this test is ignored by default
  * to run it:
  * mvn exec:java -Dexec.mainClass="org.scalatest.tools.Runner" -Dexec.classpathScope="test" -Dexec.args="-o -s fr.acinq.eclair.interop.InteroperabilitySpec"
  */
class InteroperabilitySpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll {

  import InteroperabilitySpec._

  val config = ConfigFactory.load()
  implicit val formats = org.json4s.DefaultFormats

  def osName: String = {
    val name = System.getProperty("os.name")
    if (name.contains("Windows")) {
      "windows"
    } else if (name.contains("Linux")) {
      "linux"
    } else if (name.contains("Mac OS X")) {
      "osx"
    } else {
      name.replaceAll("\\W", "")
    }
  }

  def arch: String = {
    System.getProperty("sun.arch.data.model").toInt match {
      case 32 => "32"
      case 64 => "64"
    }
  }

  val currentdir = new File(".").getCanonicalPath
  val prefix = s"src/test/resources/binaries/${osName}${arch}"

  // start bitcoind
  val bitcoinddir = Files.createTempDirectory("bitcoind")
  Files.createDirectory(Paths.get(bitcoinddir.toString, "regtest"))
  Files.write(Paths.get(bitcoinddir.toString, "bitcoin.conf"), "regtest=1\nrpcuser=foo\nrpcpassword=bar".getBytes())
  Files.write(Paths.get(bitcoinddir.toString, "regtest", "bitcoin.conf"), "regtest=1\nrpcuser=foo\nrpcpassword=bar".getBytes())
  val bitcoind = Process(s"$prefix/bitcoind -datadir=${bitcoinddir.toString} -regtest").run
  val bitcoindf = Future(blocking(bitcoind.exitValue()))
  sys.addShutdownHook(bitcoind.destroy())


  Thread.sleep(3000)
  assert(!bitcoindf.isCompleted)
  val bitcoinClient = new BitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 18332)
  val btccli = new ExtendedBitcoinClient(bitcoinClient)

  awaitAssert(Await.result(btccli.client.invoke("getblockchaininfo"), 3 seconds), 10 seconds)

  Await.result(btccli.client.invoke("generate", 500), 10 seconds)

  // start lightningd
  val lightningddir = Files.createTempDirectory("lightningd")
  val lightningd = Process(
    s"$prefix/lightningd --bitcoin-datadir=${bitcoinddir.toString + "/regtest"} --lightning-dir=${lightningddir.toString}",
    None,
    "PATH" -> s"$currentdir/$prefix").run
  val lightningdf = Future(blocking(lightningd.exitValue()))
  sys.addShutdownHook(lightningd.destroy())
  Thread.sleep(5000) // lightning now takes more time to start b/c of sqlite
  assert(!lightningdf.isCompleted)

  val chain = Await.result(bitcoinClient.invoke("getblockchaininfo").map(json => (json \ "chain").extract[String]), 10 seconds)
  assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")

  val blockchain = system.actorOf(Props(new PeerWatcher(btccli, 300)), name = "blockchain")
  val paymentHandler = system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  val register = system.actorOf(Register.props(blockchain, paymentHandler), name = "register")
  val server = system.actorOf(Server.props(config.getString("eclair.server.address"), config.getInt("eclair.server.port"), register), "server")

  val lncli = new LightingCli(s"$prefix/lightning-cli --lightning-dir=${lightningddir.toString}")
  implicit val timeout = Timeout(30 seconds)


  override protected def afterAll(): Unit = {
    bitcoind.destroy()
    lightningd.destroy()
    super.afterAll()
  }

  def sendCommand(channelId: String, cmd: Command): Future[String] = {
    system.actorSelection(Register.actorPathToChannelId(system, channelId)).resolveOne().map(actor => {
      actor ! cmd
      "ok"
    })
  }

  def connect(host: String, port: Int): Future[Unit] = {
    val address = lncli.fund
    val future = for {
      txid <- btccli.sendFromAccount("", address, 0.03)
      tx <- btccli.getRawTransaction(txid)
    } yield lncli.connect(host, port, tx)
    future
  }

  def listChannels: Future[Iterable[RES_GETINFO]] = {
    implicit val timeout = Timeout(5 seconds)
    (register ? ListChannels).mapTo[Iterable[ActorRef]]
      .flatMap(l => Future.sequence(l.map(c => (c ? CMD_GETINFO).mapTo[RES_GETINFO])))
  }

  def waitForState(state: State): Future[Unit] = {
    listChannels.map(_.map(_.state)).flatMap(current =>
      if (current.toSeq == Seq(state))
        Future.successful(())
      else {
        Thread.sleep(5000)
        waitForState(state)
      }
    )
  }

  val seed = BinaryData("0102030405060708010203040506070801020304050607080102030405060708")

  test("connect to lightningd") {
    val future = for {
      _ <- connect("localhost", 45000)
      _ <- waitForState(OPEN_WAITING_THEIRANCHOR)
    } yield ()
    Await.result(future, 30 seconds)
  }

  test("reach normal state") {
    val future = for {
      _ <- btccli.client.invoke("generate", 10)
      _ <- waitForState(NORMAL)
    } yield ()
    Await.result(future, 45 seconds)
  }

  test("fulfill HTLCs") {
    def now: Int = (System.currentTimeMillis() / 1000).toInt

    val future = for {
      channelId <- listChannels.map(_.head).map(_.channelid.toString)
      peer = lncli.getPeers.head
      // lightningd sends us a htlc
      blockcount <- btccli.getBlockCount
      _ = lncli.devroutefail(false)
      _ = lncli.newhtlc(peer.peerid, 70000000, blockcount + 288, Helpers.revocationHash(seed, 0))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      // we fulfill it
      htlcid <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.head.add.id)
      _ <- sendCommand(channelId, CMD_FULFILL_HTLC(htlcid, Helpers.revocationPreimage(seed, 0)))
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      peer1 = lncli.getPeers.head
      _ = assert(peer1.their_amount + peer1.their_fee == 70000000)
      // lightningd sends us another htlc
      _ = lncli.newhtlc(peer.peerid, 80000000, blockcount + 288, Helpers.revocationHash(seed, 1))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      htlcid1 <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.head.add.id)
      _ <- sendCommand(channelId, CMD_FULFILL_HTLC(htlcid1, Helpers.revocationPreimage(seed, 1)))
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      peer2 = lncli.getPeers.head
      _ = assert(peer2.their_amount + peer2.their_fee == 70000000 + 80000000)
      // we send lightningd a HTLC
      _ <- sendCommand(channelId, CMD_ADD_HTLC(70000000, Helpers.revocationHash(seed, 0), locktime(Blocks(blockcount.toInt + 576)), id = Some(42)))
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      // and we ask lightingd to fulfill it
      _ = lncli.fulfillhtlc(peer.peerid, 42, Helpers.revocationPreimage(seed, 0))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      c <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments)
      _ = assert(c.ourCommit.spec.amount_us_msat == 80000000)
    } yield ()
    Await.result(future, 300000 seconds)
  }

  test("close the channel") {
    val peer = lncli.getPeers.head
    lncli.close(peer.peerid)

    val future = for {
      _ <- waitForState(CLOSING)
      _ <- btccli.client.invoke("generate", 10)
      _ <- waitForState(CLOSED)
    } yield ()

    Await.result(future, 4500 seconds)
  }
}

object InteroperabilitySpec {

  object LightningCli {

    case class Peers(peers: Seq[Peer])

    case class Peer(name: String, state: String, peerid: String, our_amount: Long, our_fee: Long, their_amount: Long, their_fee: Long)

  }

  class LightingCli(path: String) {

    import LightningCli._

    implicit val formats = org.json4s.DefaultFormats

    /**
      *
      * @return a funding address that can be used to connect to another node
      */
    def fund: String = {
      val raw = s"$path newaddr" !!
      val json = parse(raw)
      val JString(address) = json \ "address"
      address
    }

    /**
      * connect to another node
      *
      * @param host node address
      * @param port node port
      * @param tx   transaction that sends money to a funding address generated with the "fund" method
      */
    def connect(host: String, port: Int, tx: String): Unit = {
      assert(s"$path connect $host $port $tx".! == 0)
    }

    def close(peerId: String): Unit = {
      assert(s"$path close $peerId".! == 0)
    }

    def getPeers: Seq[Peer] = {
      val raw = s"$path getpeers" !!

      parse(raw).extract[Peers].peers
    }

    def newhtlc(peerid: String, amount: Long, expiry: Long, rhash: BinaryData): Unit = {
      assert(s"$path newhtlc $peerid $amount $expiry $rhash".! == 0)
    }

    def fulfillhtlc(peerid: String, htlcId: Long, rhash: BinaryData): Unit = {
      assert(s"$path fulfillhtlc $peerid $htlcId $rhash".! == 0)
    }

    def commit(peerid: String): Unit = {
      assert(s"$path commit $peerid".! == 0)
    }

    def devroutefail(enable: Boolean): Unit = {
      assert(s"$path dev-routefail $enable".! == 0)
    }
  }

}

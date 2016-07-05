package fr.acinq.eclair.channel

import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.Globals._
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PollingWatcher}
import fr.acinq.eclair.channel.Register.ListChannels
import fr.acinq.eclair.io.Server
import lightning.locktime
import lightning.locktime.Locktime.{Blocks, Seconds}
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods._
import org.scalatest.FunSuiteLike

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._

/**
  * this test is ignored by default
  * to run it:
  * mvn exec:java -Dexec.mainClass="org.scalatest.tools.Runner" \
  * -Dexec.classpathScope="test" -Dexec.args="-s fr.acinq.eclair.channel.InteroperabilitySpec" \
  * -Dlightning-cli.path=/home/fabrice/code/lightning-c.fdrn/daemon/lightning-cli
  *
  * You don't have to specify where lightning-cli is if it is on the PATH
  */
class InteroperabilitySpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import InteroperabilitySpec._

  val config = ConfigFactory.load()
  implicit val formats = org.json4s.DefaultFormats

  val chain = Await.result(bitcoin_client.invoke("getblockchaininfo").map(json => (json \ "chain").extract[String]), 10 seconds)
  assert(chain == "testnet" || chain == "regtest" || chain == "segnet4", "you should be on testnet or regtest or segnet4")

  val blockchain = system.actorOf(Props(new PollingWatcher(new ExtendedBitcoinClient(bitcoin_client))), name = "blockchain")
  val register = system.actorOf(Register.props(blockchain), name = "register")
  val server = system.actorOf(Server.props(config.getString("eclair.server.address"), config.getInt("eclair.server.port"), register), "server")

  val lncli = new LightingCli(config.getString("lightning-cli.path"))
  val btccli = new ExtendedBitcoinClient(Globals.bitcoin_client)
  implicit val timeout = Timeout(30 seconds)

  def actorPathToChannelId(channelId: BinaryData): ActorPath =
    system / "register" / "handler-*" / "channel" / s"*-${channelId}"

  def sendCommand(channel_id: String, cmd: Command): Future[String] = {
    system.actorSelection(actorPathToChannelId(channel_id)).resolveOne().map(actor => {
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

  test("connect to lighningd") {
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
      // lightningd send us a htlc
      blockcount <- btccli.getBlockCount
      _ = lncli.devroutefail(false)
      _ = lncli.newhtlc(peer.peerid, 70000000, blockcount + 288, Helpers.revocationHash(seed, 0))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      htlcid <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.head.id)
      _ <- sendCommand(channelId, CMD_FULFILL_HTLC(htlcid, Helpers.revocationPreimage(seed, 0)))
      _ <- sendCommand(channelId, CMD_SIGN)
      peer1 = lncli.getPeers.head
      _ = assert(peer1.their_amount + peer1.their_fee == 70000000)
      // lightningd send us another htlc
      _ = lncli.newhtlc(peer.peerid, 80000000, blockcount + 288, Helpers.revocationHash(seed, 1))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      htlcid1 <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.head.id)
      _ <- sendCommand(channelId, CMD_FULFILL_HTLC(htlcid1, Helpers.revocationPreimage(seed, 1)))
      _ <- sendCommand(channelId, CMD_SIGN)
      peer2 = lncli.getPeers.head
      _ = assert(peer2.their_amount + peer2.their_fee == 70000000 + 80000000)
      // we send lightningd a HTLC
      _ = println(s"htlc payment hash: ${Helpers.revocationHash(seed, 0)}")
      _ <- sendCommand(channelId, CMD_ADD_HTLC(70000000, Helpers.revocationHash(seed, 0), locktime(Blocks(blockcount.toInt + 576))))
      _ <- sendCommand(channelId, CMD_SIGN)
      _ = Thread.sleep(500)
      _ = println(s"htlc payment preimage: ${Helpers.revocationPreimage(seed, 0)}")
      _ = lncli.fulfillhtlc(peer.peerid, Helpers.revocationPreimage(seed, 0))
      _ = Thread.sleep(500)
      _ <- sendCommand(channelId, CMD_SIGN)
      c <- listChannels.map(_.head).map(_.data.asInstanceOf[DATA_NORMAL].commitments)
      _ = assert(c.ourCommit.spec.amount_us_msat == 80000000)
    } yield ()
    Await.result(future, 3000 seconds)
  }

  test("close the channel") {
    val peer = lncli.getPeers.head
    lncli.close(peer.peerid)

    val future = for {
      _ <- waitForState(CLOSING)
      _ <- btccli.client.invoke("generate", 10)
      _ <- waitForState(CLOSED)
    } yield ()

    Await.result(future, 45 seconds)
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

    def fulfillhtlc(peerid: String, rhash: BinaryData): Unit = {
      assert(s"$path fulfillhtlc $peerid $rhash".! == 0)
    }

    def commit(peerid: String): Unit = {
      assert(s"$path commit $peerid".! == 0)
    }

    def devroutefail(enable: Boolean): Unit = {
      assert(s"$path dev-routefail $enable".! == 0)
    }
  }

}

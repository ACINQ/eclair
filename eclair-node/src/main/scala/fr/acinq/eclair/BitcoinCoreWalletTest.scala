package fr.acinq.eclair

import java.io.File

import akka.actor.{ActorSystem, Props, SupervisorStrategy}
import fr.acinq.bitcoin.{Satoshi, Script}
import fr.acinq.eclair.blockchain.ZmqWatcher
import fr.acinq.eclair.blockchain.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.wallet.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.zmq.ZMQActor
import fr.acinq.eclair.transactions.Scripts
import grizzled.slf4j.Logging

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

/**
  * Created by PM on 06/07/2017.
  */
object BitcoinCoreWalletTest extends App with Logging {

  implicit val system = ActorSystem("system")

  val datadir = new File(".")
  val config = NodeParams.loadConfiguration(datadir)
  val nodeParams = NodeParams.makeNodeParams(datadir, config, "")

  val bitcoinClient = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("bitcoind.rpcuser"),
    password = config.getString("bitcoind.rpcpassword"),
    host = config.getString("bitcoind.host"),
    port = config.getInt("bitcoind.rpcport")))

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global

  val zmq = system.actorOf(SimpleSupervisor.props(Props(new ZMQActor(config.getString("bitcoind.zmq"), None)), "zmq", SupervisorStrategy.Restart))
  val watcher = system.actorOf(SimpleSupervisor.props(ZmqWatcher.props(nodeParams, bitcoinClient), "watcher", SupervisorStrategy.Resume))

  val wallet = new BitcoinCoreWallet(bitcoinClient.rpcClient, watcher)

  logger.info("hello")
  val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
  val result = Await.result(wallet.makeFundingTx(fundingPubkeyScript, Satoshi(1000000L), 20000), 30 minutes)
  println(result)

}

package fr.acinq.eclair.recovery

import akka.actor.Props
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.Kit
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.recovery.RecoveryFSM.RecoveryConnect
import grizzled.slf4j.Logging

import scala.util.{Failure, Random, Success, Try}

object RecoveryTool extends Logging {

  private lazy val scanner = new java.util.Scanner(System.in).useDelimiter("\\n")

  def interactiveRecovery(appKit: Kit): Unit = {
    println(s"\n ### Welcome to the eclair recovery tool ### \n")
    val nodeUri = getInput[NodeURI]("Please insert the URI of the target node: ", NodeURI.parse)
    println(s"### Attempting channel recovery now - good luck! ###")

    implicit val shttp = OkHttpFutureBackend()

    val bitcoinRpcClient = new BasicBitcoinJsonRPCClient(
      user = appKit.nodeParams.config.getString("bitcoind.rpcuser"),
      password = appKit.nodeParams.config.getString("bitcoind.rpcpassword"),
      host = appKit.nodeParams.config.getString("bitcoind.host"),
      port = appKit.nodeParams.config.getInt("bitcoind.rpcport")
    )

    val recoveryFSM = appKit.system.actorOf(Props(new RecoveryFSM(appKit.nodeParams, appKit.authenticator, appKit.router, appKit.switchboard, appKit.wallet, appKit.watcher, appKit.relayer, bitcoinRpcClient)), RecoveryFSM.actorName)
    recoveryFSM ! RecoveryConnect(nodeUri)
  }

  private def getInput[T](msg: String, parse: String => T): T = {
    do {
      print(msg)
      Try(parse(scanner.next())) match {
        case Success(someT) => return someT
        case Failure(thr) => println(s"Error: ${thr.getMessage}")
      }
    } while (true)

    throw new IllegalArgumentException("Unable to get input")
  }
}

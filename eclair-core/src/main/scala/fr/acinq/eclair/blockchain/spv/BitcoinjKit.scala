package fr.acinq.eclair.blockchain.spv

import java.io.File
import java.util

import akka.actor.ActorSystem
import fr.acinq.bitcoin.{BinaryData, Transaction}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.spv.BitcoinjKit._
import fr.acinq.eclair.blockchain.{CurrentBlockCount, NewConfidenceLevel}
import grizzled.slf4j.Logging
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.core.listeners.{NewBestBlockListener, OnTransactionBroadcastListener, PeerConnectedEventListener, TransactionConfidenceEventListener}
import org.bitcoinj.core.{NetworkParameters, Peer, StoredBlock, Transaction => BitcoinjTransaction}
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{RegTestParams, TestNet3Params}
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.listeners.ScriptsChangeEventListener

import scala.concurrent.Promise

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinjKit(chain: String, datadir: File)(implicit system: ActorSystem) extends WalletAppKit(chain2Params(chain), datadir, "bitcoinj") with Logging {

  // so that we know when the peerGroup/chain/wallet are accessible
  private val initializedPromise = Promise[Boolean]()
  val initialized = initializedPromise.future

  override def onSetupCompleted(): Unit = {

    logger.info(s"peerGroup.getMaxConnections==${peerGroup().getMaxConnections}")
    logger.info(s"peerGroup.getMinBroadcastConnections==${peerGroup().getMinBroadcastConnections}")

    // as soon as we are connected the peers will tell us their current height and we will advertise it immediately
    peerGroup().addConnectedEventListener(new PeerConnectedEventListener {
      override def onPeerConnected(peer: Peer, peerCount: Int): Unit = {
        val blockCount = peerGroup().getMostCommonChainHeight
        // we wait for at least 3 peers before relying on current block height, but we trust localhost
        if ((peer.getAddress.getAddr.isLoopbackAddress || peerCount > 3) && Globals.blockCount.get() < blockCount) {
          logger.info(s"current blockchain height=$blockCount")
          system.eventStream.publish(CurrentBlockCount(blockCount))
          Globals.blockCount.set(blockCount)
        }
      }
    })

    peerGroup().addOnTransactionBroadcastListener(new OnTransactionBroadcastListener {
      override def onTransaction(peer: Peer, t: BitcoinjTransaction): Unit = {
        logger.info(s"txid=${t.getHashAsString} confidence=${t.getConfidence}")
      }
    })

    chain().addNewBestBlockListener(new NewBestBlockListener {
      override def notifyNewBestBlock(storedBlock: StoredBlock): Unit = {
        // when synchronizing we don't want to advertise previous blocks
        if (Globals.blockCount.get() < storedBlock.getHeight) {
          logger.debug(s"new block height=${storedBlock.getHeight} hash=${storedBlock.getHeader.getHashAsString}")
          system.eventStream.publish(CurrentBlockCount(storedBlock.getHeight))
          Globals.blockCount.set(storedBlock.getHeight)
        }
      }
    })

    wallet().addTransactionConfidenceEventListener(new TransactionConfidenceEventListener {
      override def onTransactionConfidenceChanged(wallet: Wallet, bitcoinjTx: BitcoinjTransaction): Unit = {
        val tx = Transaction.read(bitcoinjTx.bitcoinSerialize())
        logger.info(s"tx confidence changed for txid=${tx.txid} confidence=${bitcoinjTx.getConfidence}")
        val depthInBlocks = bitcoinjTx.getConfidence.getConfidenceType match {
          case ConfidenceType.DEAD => -1
          case _ => bitcoinjTx.getConfidence.getDepthInBlocks
        }
        system.eventStream.publish(NewConfidenceLevel(tx, 0, depthInBlocks))
      }
    })

    wallet.addScriptsChangeEventListener(new ScriptsChangeEventListener {

      import scala.collection.JavaConversions._

      override def onScriptsChanged(wallet: Wallet, scripts: util.List[Script], isAddingScripts: Boolean): Unit =
        logger.info(s"watching scripts: ${scripts.map(_.getProgram).map(BinaryData(_)).mkString(",")}")
    })

    initializedPromise.success(true)
  }

}

object BitcoinjKit {

  def chain2Params(chain: String): NetworkParameters = chain match {
    case "regtest" => RegTestParams.get()
    case "test" => TestNet3Params.get()
  }
}

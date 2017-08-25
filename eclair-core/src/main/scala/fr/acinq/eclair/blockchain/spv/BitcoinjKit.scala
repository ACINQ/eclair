package fr.acinq.eclair.blockchain.spv

import java.io.File

import akka.actor.ActorSystem
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.spv.BitcoinjKit._
import fr.acinq.eclair.blockchain.{CurrentBlockCount, NewConfidenceLevel}
import grizzled.slf4j.Logging
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.core.listeners.{NewBestBlockListener, PeerConnectedEventListener, TransactionConfidenceEventListener}
import org.bitcoinj.core.{NetworkParameters, Peer, StoredBlock, Transaction => BitcoinjTransaction}
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{RegTestParams, TestNet3Params}
import org.bitcoinj.wallet.Wallet

import scala.concurrent.Promise

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinjKit(chain: String, datadir: File)(implicit system: ActorSystem) extends WalletAppKit(chain2Params(chain), datadir, "bitcoinj", true) with Logging {

  // tells us when the peerGroup/chain/wallet are accessible
  private val initializedPromise = Promise[Boolean]()
  val initialized = initializedPromise.future

  // tells us as soon as we know the current block height
  private val atCurrentHeightPromise = Promise[Boolean]()
  val atCurrentHeight = atCurrentHeightPromise.future

  // tells us when we are at current block height
//  private val syncedPromise = Promise[Boolean]()
//  val synced = syncedPromise.future

  private def updateBlockCount(blockCount: Int) = {
    // when synchronizing we don't want to advertise previous blocks
    if (Globals.blockCount.get() < blockCount) {
      logger.debug(s"current blockchain height=$blockCount")
      system.eventStream.publish(CurrentBlockCount(blockCount))
      Globals.blockCount.set(blockCount)
    }
  }

  override def onSetupCompleted(): Unit = {

    logger.info(s"peerGroup.getMinBroadcastConnections==${peerGroup().getMinBroadcastConnections}")
    logger.info(s"peerGroup.getMinBroadcastConnections==${peerGroup().getMinBroadcastConnections}")

//    setDownloadListener(new DownloadProgressTracker {
//      override def doneDownload(): Unit = {
//        super.doneDownload()
//        // may be called multiple times
//        syncedPromise.trySuccess(true)
//      }
//    })

    // we set the blockcount to the previous stored block height
    updateBlockCount(chain().getBestChainHeight)

    // as soon as we are connected the peers will tell us their current height and we will advertise it immediately
    peerGroup().addConnectedEventListener(new PeerConnectedEventListener {
      override def onPeerConnected(peer: Peer, peerCount: Int): Unit =
      // we wait for at least 3 peers before relying on the information they are giving, but we trust localhost
        if (peer.getAddress.getAddr.isLoopbackAddress || peerCount > 3) {
          updateBlockCount(peerGroup().getMostCommonChainHeight)
          // may be called multiple times
          atCurrentHeightPromise.trySuccess(true)
        }
    })

    chain().addNewBestBlockListener(new NewBestBlockListener {
      override def notifyNewBestBlock(storedBlock: StoredBlock): Unit =
        updateBlockCount(storedBlock.getHeight)
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

    initializedPromise.success(true)
  }

}

object BitcoinjKit {

  def chain2Params(chain: String): NetworkParameters = chain match {
    case "regtest" => RegTestParams.get()
    case "test" => TestNet3Params.get()
  }
}

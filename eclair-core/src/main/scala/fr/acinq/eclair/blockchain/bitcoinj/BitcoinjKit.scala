package fr.acinq.eclair.blockchain.bitcoinj

import java.io.File
import java.net.InetSocketAddress

import akka.actor.ActorSystem
import com.google.common.util.concurrent.{FutureCallback, Futures}
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.bitcoinj.BitcoinjKit._
import fr.acinq.eclair.blockchain.CurrentBlockCount
import grizzled.slf4j.Logging
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.core.listeners._
import org.bitcoinj.core.{Block, Context, FilteredBlock, NetworkParameters, Peer, PeerAddress, StoredBlock, VersionMessage, Transaction => BitcoinjTransaction}
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.params.{RegTestParams, TestNet3Params}
import org.bitcoinj.utils.Threading
import org.bitcoinj.wallet.Wallet

import scala.collection.JavaConversions._
import scala.concurrent.Promise
import scala.util.Try

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinjKit(chain: String, datadir: File, staticPeers: List[InetSocketAddress] = Nil)(implicit system: ActorSystem) extends WalletAppKit(chain2Params(chain), datadir, "bitcoinj", true) with Logging {

  if (staticPeers.size > 0) {
    logger.info(s"using staticPeers=${staticPeers.mkString(",")}")
    setPeerNodes(staticPeers.map(addr => new PeerAddress(params, addr)).head)
  }

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

    peerGroup().setMinRequiredProtocolVersion(70015) // bitcoin core 0.13
    wallet().watchMode = true

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
      override def onPeerConnected(peer: Peer, peerCount: Int): Unit = {
        if ((peer.getPeerVersionMessage.localServices & VersionMessage.NODE_WITNESS) == 0) {
          peer.close()
        } else {
          Context.propagate(wallet.getContext)
          // we wait for at least 3 peers before relying on the information they are giving, but we trust localhost
          if (peer.getAddress.getAddr.isLoopbackAddress || peerCount > 3) {
            updateBlockCount(peerGroup().getMostCommonChainHeight)
            // may be called multiple times
            atCurrentHeightPromise.trySuccess(true)
          }
        }
      }
    })

    peerGroup.addBlocksDownloadedEventListener(new BlocksDownloadedEventListener {
      override def onBlocksDownloaded(peer: Peer, block: Block, filteredBlock: FilteredBlock, blocksLeft: Int): Unit = {
        Context.propagate(wallet.getContext)
        logger.debug(s"received block=${block.getHashAsString} (size=${block.bitcoinSerialize().size} txs=${Try(block.getTransactions.size).getOrElse(-1)}) filteredBlock=${Try(filteredBlock.getHash.toString).getOrElse("N/A")} (size=${Try(block.bitcoinSerialize().size).getOrElse(-1)} txs=${Try(filteredBlock.getTransactionCount).getOrElse(-1)})")
        Try {
          if (filteredBlock.getAssociatedTransactions.size() > 0) {
            logger.info(s"retrieving full block ${block.getHashAsString}")
            Futures.addCallback(peer.getBlock(block.getHash), new FutureCallback[Block] {
              override def onFailure(throwable: Throwable) = logger.error(s"could not retrieve full block=${block.getHashAsString}")

              override def onSuccess(fullBlock: Block) = {
                Try {
                  Context.propagate(wallet.getContext)
                  fullBlock.getTransactions.foreach {
                    case tx =>
                      logger.debug(s"received tx=${tx.getHashAsString} witness=${Transaction.read(tx.bitcoinSerialize()).txIn(0).witness.stack.size} from fullBlock=${fullBlock.getHash} confidence=${tx.getConfidence}")
                      val depthInBlocks = tx.getConfidence.getConfidenceType match {
                        case ConfidenceType.DEAD => -1
                        case _ => tx.getConfidence.getDepthInBlocks
                      }
                      system.eventStream.publish(NewConfidenceLevel(Transaction.read(tx.bitcoinSerialize()), 0, depthInBlocks))
                  }
                }
              }
            }, Threading.USER_THREAD)
          }
        }
      }
    })

    chain().addNewBestBlockListener(new NewBestBlockListener {
      override def notifyNewBestBlock(storedBlock: StoredBlock): Unit =
        updateBlockCount(storedBlock.getHeight)
    })

    wallet().addTransactionConfidenceEventListener(new TransactionConfidenceEventListener {
      override def onTransactionConfidenceChanged(wallet: Wallet, bitcoinjTx: BitcoinjTransaction): Unit = {
        Context.propagate(wallet.getContext)
        val tx = Transaction.read(bitcoinjTx.bitcoinSerialize())
        logger.info(s"tx confidence changed for txid=${tx.txid} confidence=${bitcoinjTx.getConfidence} witness=${bitcoinjTx.getWitness(0)}")
        val (blockHeight, confirmations) = bitcoinjTx.getConfidence.getConfidenceType match {
          case ConfidenceType.DEAD => (-1, -1)
          case ConfidenceType.BUILDING => (bitcoinjTx.getConfidence.getAppearedAtChainHeight, bitcoinjTx.getConfidence.getDepthInBlocks)
          case _ => (-1, bitcoinjTx.getConfidence.getDepthInBlocks)
        }
        system.eventStream.publish(NewConfidenceLevel(tx, blockHeight, confirmations))
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

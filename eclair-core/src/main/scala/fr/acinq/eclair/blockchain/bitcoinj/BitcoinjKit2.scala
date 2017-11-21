package fr.acinq.eclair.blockchain.bitcoinj

import java.io.File

import fr.acinq.eclair.blockchain.bitcoinj.BitcoinjKit._
import grizzled.slf4j.Logging
import org.bitcoinj.core.listeners.PeerConnectedEventListener
import org.bitcoinj.core.{Peer, VersionMessage}
import org.bitcoinj.kits.WalletAppKit

import scala.concurrent.Promise

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinjKit2(chain: String, datadir: File) extends WalletAppKit(chain2Params(chain), datadir, "bitcoinj-wallet", true) with Logging {

  // so that we know when the peerGroup/chain/wallet are accessible
  private val initializedPromise = Promise[Boolean]()
  val initialized = initializedPromise.future

  override def onSetupCompleted(): Unit = {

    peerGroup().setMinRequiredProtocolVersion(70015) // bitcoin core 0.13

    peerGroup().addConnectedEventListener(new PeerConnectedEventListener {
      override def onPeerConnected(peer: Peer, peerCount: Int): Unit = {
        if ((peer.getPeerVersionMessage.localServices & VersionMessage.NODE_WITNESS) == 0) {
          peer.close()
        }
      }
    })

    initializedPromise.success(true)
  }

}



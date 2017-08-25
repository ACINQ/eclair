package fr.acinq.eclair.blockchain.spv

import java.io.File

import akka.actor.ActorSystem
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.spv.BitcoinjKit._
import fr.acinq.eclair.blockchain.{CurrentBlockCount, NewConfidenceLevel}
import grizzled.slf4j.Logging
import org.bitcoinj.core.listeners.{NewBestBlockListener, PeerConnectedEventListener, TransactionConfidenceEventListener}
import org.bitcoinj.core.{Peer, StoredBlock, Transaction => BitcoinjTransaction}
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.wallet.Wallet

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 09/07/2017.
  */
class BitcoinjKit2(chain: String, datadir: File) extends WalletAppKit(chain2Params(chain), datadir, "bitcoinj-wallet", true) with Logging {

  // so that we know when the peerGroup/chain/wallet are accessible
  private val initializedPromise = Promise[Boolean]()
  val initialized = initializedPromise.future

  override def onSetupCompleted(): Unit = {

    initializedPromise.success(true)
  }

}



package fr.acinq.eclair.blockchain.electrum

import java.io.File
import java.security.SecureRandom

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Terminated}
import com.google.common.io.Files
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, derivePrivateKey, hardened}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Block, Crypto, DeterministicWallet, MnemonicCode, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{GetTransaction, GetTransactionResponse}
import grizzled.slf4j.Logging
import scodec.Codec

import scala.annotation.tailrec
import scala.util.Try

class ElectrumWallet(mnemonics: Seq[String], client: ActorRef, params: ElectrumWallet.WalletParameters) extends Actor with Stash with ActorLogging {

  import ElectrumWallet._

  val seed = MnemonicCode.toSeed(mnemonics, "")
  val master = DeterministicWallet.generate(seed)

  client ! ElectrumClient.AddStatusListener(self)

  import params._

  val accountMaster = accountKey(master)
  val accountIndex = 0

  val changeMaster = changeKey(master)
  val changeIndex = 0

  val firstAccountKeys = (0 until 10).map(i => derivePrivateKey(accountMaster, i)).toVector
  val firstChangeKeys = (0 until 10).map(i => derivePrivateKey(changeMaster, i)).toVector

  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  val header = chainHash match {
    case Block.RegtestGenesisBlock.hash => ElectrumClient.Header.RegtestGenesisHeader
    case Block.TestnetGenesisBlock.hash => ElectrumClient.Header.TestnetGenesisHeader
  }

  // disconnected --> waitingForTip --> running --
  // ^                                            |
  // |                                            |
  //  --------------------------------------------

  def receive = disconnected(State(header, firstAccountKeys, firstChangeKeys))

  def disconnected(state: State): Receive = {
    case ElectrumClient.Ready =>
      client ! ElectrumClient.HeaderSubscription(self)
      context become waitingForTip(state)

    case GetCurrentReceiveAddress => sender ! GetCurrentReceiveAddressResponse(state.currentReceiveAddress)

    case GetBalance =>
      val (confirmed, unconfirmed) = state.balance
      sender ! GetBalanceResponse(confirmed, unconfirmed)

    case GetState => sender ! GetStateResponse(state)


  }

  def waitingForTip(state: State): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(header) =>
      var hashes = collection.mutable.HashSet.empty[BinaryData]
      (state.accountKeys ++ state.changeKeys).map(key => {
        val hash = computeScriptHash(key.publicKey)
        hashes += hash
        client ! ElectrumClient.ScriptHashSubscription(hash, self)
      })
      context become running(state.copy(tip = header, pendingScriptHashSubscriptionRequests = state.pendingScriptHashSubscriptionRequests ++ hashes))

    case GetState => sender ! GetStateResponse(state)

    case GetCurrentReceiveAddress => sender ! GetCurrentReceiveAddressResponse(state.currentReceiveAddress)

    case GetBalance =>
      val (confirmed, unconfirmed) = state.balance
      sender ! GetBalanceResponse(confirmed, unconfirmed)

    case ElectrumClient.Disconnected =>
      log.info(s"wallet got disconnected")
      context become disconnected(state)
  }

  def running(state: State): Receive = {
    case ElectrumClient.HeaderSubscriptionResponse(header) if state.tip == header => ()

    case ElectrumClient.HeaderSubscriptionResponse(header) =>
      log.info(s"got new tip ${header.block_hash} at ${header.block_height}")
      state.heights.collect {
        case (txid, height) if height > 0 => statusListeners.map(_ ! WalletTransactionConfidenceChanged(txid, header.block_height - height + 1))
      }
      context become running(state.copy(tip = header))

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) if state.status.get(scriptHash) == Some(status) => () // we already have it

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) if !state.accountKeyMap.contains(scriptHash) && !state.changeKeyMap.contains(scriptHash) =>
      log.warning(s"received status [$status] for script hash $scriptHash which does not match any of our keys")

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      val key = state.accountKeyMap.getOrElse(scriptHash, state.changeKeyMap(scriptHash))
      log.info(s"new status [$status] for script hash $scriptHash for our key ${segwitAddress(key)}")

      // ask for unspents and history
      client ! ElectrumClient.ScriptHashListUnspent(scriptHash)
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)

      val state1 = state.copy(status = state.status + (scriptHash -> status),
        pendingScriptHashSubscriptionRequests = state.pendingScriptHashSubscriptionRequests - scriptHash,
        pendingHistoryRequests = state.pendingHistoryRequests + scriptHash)

      if (state1.status.size == state1.accountKeys.size + state1.changeKeys.size) {
        // we have status info for all our keys, we can tell if we need new keys
        val newAccountKeys = {
          val start = state1.accountKeys.last.path.lastChildNumber + 1
          val end = start + swipeRange - state1.unusedAccountKeys.size
          log.info(s"generating ${end - start + 1} account key(s) with number $start to $end")
          val newkeys = (start until end).map(i => derivePrivateKey(accountMaster, i)).toVector
          newkeys
        }
        val newChangeKeys = {
          val start = state1.changeKeys.last.path.lastChildNumber + 1
          val end = start + swipeRange - state1.unusedChangedKeys.size
          log.info(s"generating ${end - start + 1} change key(s) with number $start to $end")
          val newkeys = (start until end).map(i => derivePrivateKey(changeMaster, i)).toVector
          newkeys
        }
        val hashes = collection.mutable.HashSet.empty[BinaryData]
        (newAccountKeys ++ newChangeKeys).map(key => {
          val hash = computeScriptHash(key.publicKey)
          hashes += hash
          client ! ElectrumClient.ScriptHashSubscription(hash, self)
        })
        val state2 = state1.copy(accountKeys = state1.accountKeys ++ newAccountKeys, changeKeys = state1.changeKeys ++ newChangeKeys, pendingScriptHashSubscriptionRequests = state1.pendingScriptHashSubscriptionRequests ++ hashes)
        if (state2.isReady) {
          val ready = state2.readyMessage
          log.info(s"wallet is ready with $ready")
          statusListeners.map(_ ! ready)
        }
        context become running(state2)
      } else {
        context become running(state1)
      }

    case ElectrumClient.ScriptHashListUnspentResponse(scriptHash, unspents) =>
      log.debug(s"script hash $scriptHash unspents $unspents")
      var heights1 = state.heights
      val hashes = collection.mutable.HashSet.empty[BinaryData]
      unspents.map(item => {
        if (!state.transactions.contains(BinaryData(item.tx_hash))) {
          hashes += item.tx_hash
          client ! GetTransaction(item.tx_hash)
        }
        heights1 = heights1 + (BinaryData(item.tx_hash) -> item.height)
      })
      val state1 = state.copy(heights = heights1, unspents = state.unspents + (scriptHash -> unspents.toSet), pendingTransactionRequests = state.pendingTransactionRequests ++ hashes)
      context become running(state1)

    case ElectrumClient.GetScriptHashHistoryResponse(scriptHash, history) =>
      log.debug(s"script hash $scriptHash history $history")
      var heights1 = state.heights
      val hashes = collection.mutable.HashSet.empty[BinaryData]
      history.map(item => {
        if (!state.transactions.contains(BinaryData(item.tx_hash))) {
          hashes += item.tx_hash
          client ! GetTransaction(item.tx_hash)
        }
        heights1 = heights1 + (BinaryData(item.tx_hash) -> item.height)
      })
      val state1 = state.copy(heights = heights1, history = state.history + (scriptHash -> history), pendingHistoryRequests = state.pendingHistoryRequests - scriptHash, pendingTransactionRequests = state.pendingTransactionRequests ++ hashes)
      if (state1.isReady) {
        val ready = state1.readyMessage
        log.info(s"wallet is ready with $ready")
        statusListeners.map(_ ! ready)
      }
      context become running(state1)

    case GetTransactionResponse(tx) =>
      log.debug(s"received transaction ${tx.txid}")
      val (received, sent) = state.computeTransactionDelta(tx)
      statusListeners.map(_ ! WalletTransactionReceive(tx, state.computeTransactionDepth(tx.txid), received, sent))
      val state1 = state.copy(transactions = state.transactions + (tx.txid -> tx), pendingTransactionRequests = state.pendingTransactionRequests - tx.txid)
      if (state1.isReady) {
        val ready = state1.readyMessage
        log.info(s"wallet is ready with $ready")
        statusListeners.map(_ ! ready)
      }
      context become running(state1)

    case CompleteTransaction(tx, feeRatePerKw, allowSpendingUnconfirmed) =>
      try {
        val (state1, tx1) = state.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)
        sender ! CompleteTransactionResponse(tx1, None)
        context become running(state1)
      }
      catch {
        case t: Throwable => sender ! CompleteTransactionResponse(tx, Some(t))
      }

    case CancelTransaction(tx) =>
      sender ! CancelTransactionResponse(tx)
      context become running(state.cancelTransaction(tx))

    case bc@ElectrumClient.BroadcastTransaction(tx) =>
      val replyTo = sender()
      client ! bc
      context become {
        case resp@ElectrumClient.BroadcastTransactionResponse(tx, None) =>
          //tx broadcast successfully: commit tx
          replyTo ! resp
          unstashAll()
          val state1 = state.commitTransaction(tx)
          val (received, sent) = state.computeTransactionDelta(tx)
          statusListeners.map(_ ! WalletTransactionReceive(tx, state1.computeTransactionDepth(tx.txid), received, sent))
          context become running(state1)
        case resp@ElectrumClient.BroadcastTransactionResponse(_, Some(error)) =>
          //tx broadcast failed: cancel tx
          log.error(s"cannot broadcast tx ${tx.txid}: $error")
          replyTo ! resp
          unstashAll()
          context become running(state.cancelTransaction(tx))
        case ElectrumClient.ServerError(ElectrumClient.BroadcastTransaction(tx1), error) if tx1 == tx =>
          //tx broadcast failed: cancel tx
          log.error(s"cannot broadcast tx ${tx.txid}: $error")
          replyTo ! ElectrumClient.BroadcastTransactionResponse(tx, Some(error))
          unstashAll()
          context become running(state.cancelTransaction(tx))
        case other =>
          log.debug(s"received $other while waiting for a broadcast response")
          stash()
      }

    case GetCurrentReceiveAddress => sender ! GetCurrentReceiveAddressResponse(state.currentReceiveAddress)

    case GetBalance =>
      val (confirmed, unconfirmed) = state.balance
      sender ! GetBalanceResponse(confirmed, unconfirmed)

    case GetState => sender ! GetStateResponse(state)

    case ElectrumClient.Disconnected =>
      log.info(s"wallet got disconnected")
      context become disconnected(state)
  }

  override def unhandled(message: Any): Unit = {
    message match {
      case GetMnemonicCode =>
        sender ! GetMnemonicCodeResponse(mnemonics)
      case CommitTransaction(tx) =>
        sender ! CommitTransactionResponse(tx, Some(new RuntimeException("wallet is not connected")))
      case ElectrumClient.BroadcastTransaction(tx) =>
        sender ! ElectrumClient.BroadcastTransactionResponse(tx, Some("wallet is not connected"))
      case ElectrumClient.AddStatusListener(actor) =>
        context.watch(actor)
        statusListeners += actor
      case Terminated(actor) =>
        statusListeners -= actor
      case _ =>
        log.warning(s"received unhandled message $message")
    }
  }
}

object ElectrumWallet {

  def props(mnemonics: Seq[String], client: ActorRef, params: WalletParameters): Props = {
    val seed = MnemonicCode.toSeed(mnemonics, "")
    Props(new ElectrumWallet(mnemonics, client, params))
  }

  def props(file: File, client: ActorRef, params: WalletParameters): Props = {
    val entropy: BinaryData = (file.exists(), file.canRead(), file.isFile) match {
      case (true, true, true) => Files.toByteArray(file)
      case (false, _, _) =>
        val random = new SecureRandom()
        val buffer = new Array[Byte](16)
        random.nextBytes(buffer)
        Files.write(buffer, file)
        buffer
      case _ => throw new IllegalArgumentException(s"cannot create wallet:$file exist but cannot read from")
    }
    val mnemonics = MnemonicCode.toMnemonics(entropy)
    Props(new ElectrumWallet(mnemonics, client, params))
  }

  case class WalletParameters(chainHash: BinaryData, feeRatePerKw: Int = 20000, minimumFee: Satoshi = Satoshi(2000), dustLimit: Satoshi = Satoshi(546), swipeRange: Int = 10)

  // @formatter:off
  sealed trait Request
  sealed trait Response

  case object GetMnemonicCode extends RuntimeException
  case class GetMnemonicCodeResponse(mnemonics: Seq[String]) extends Response

  case object GetBalance extends Request
  case class GetBalanceResponse(confirmed: Satoshi, unconfirmed: Satoshi) extends Response

  case object GetCurrentReceiveAddress extends Request
  case class GetCurrentReceiveAddressResponse(address: String) extends Response

  case object GetState extends Request
  case class GetStateResponse(state: State) extends Response

  case class CompleteTransaction(tx: Transaction, feeRatePerKw: Long, allowSpendingUnconfirmed: Boolean) extends Request
  case class CompleteTransactionResponse(tx: Transaction, error: Option[Throwable]) extends Response

  case class CommitTransaction(tx: Transaction) extends Request
  case class CommitTransactionResponse(tx: Transaction, error: Option[Throwable]) extends Response

  case class SendTransaction(tx: Transaction) extends Request
  case class SendTransactionReponse(tx: Transaction) extends Response

  case class CancelTransaction(tx: Transaction) extends Request
  case class CancelTransactionResponse(tx: Transaction) extends Response

  case object InsufficientFunds extends Response
  case class AmountBelowDustLimit(dustLimit: Satoshi) extends Response

  case class GetPrivateKey(address: String) extends Request
  case class GetPrivateKeyResponse(address: String, key: Option[ExtendedPrivateKey]) extends Response

  case class WalletTransactionReceive(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi)
  case class WalletTransactionConfidenceChanged(txid: BinaryData, depth: Long)

  case class Ready(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long)
  // @formatter:off

  /**
    *
    * @param key public key
    * @return the address of the p2sh-of-p2wpkh script for this key
    */
  def segwitAddress(key: PublicKey): String = {
    val script = Script.pay2wpkh(key)
    val hash = Crypto.hash160(Script.write(script))
    Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
  }

  def segwitAddress(key: ExtendedPrivateKey): String = segwitAddress(key.publicKey)

  def segwitAddress(key: PrivateKey): String = segwitAddress(key.publicKey)

  /**
    *
    * @param key public key
    * @return a p2sh-of-p2wpkh script for this key
    */
  def computePublicKeyScript(key: PublicKey) = Script.pay2sh(Script.pay2wpkh(key))

  /**
    *
    * @param key public key
    * @return the hash of the public key script for this key, as used by ElectrumX's hash-based methods
    */
  def computeScriptHash(key: PublicKey): BinaryData = Crypto.sha256(Script.write(computePublicKeyScript(key))).reverse

  /**
    *
    * @param master master key
    * @return the BIP44 account key for this master key: m/44'/1'/0'/0
    */
  def accountKey(master: ExtendedPrivateKey) = DeterministicWallet.derivePrivateKey(master, hardened(44) :: hardened(1) :: hardened(0) :: 0L :: Nil)

  /**
    *
    * @param master master key
    * @return the BIP44 change key for this master key: m/44'/1'/0'/1
    */
  def changeKey(master: ExtendedPrivateKey) = DeterministicWallet.derivePrivateKey(master, hardened(44) :: hardened(1) :: hardened(0) :: 1L :: Nil)

  def totalAmount(utxos: Seq[Utxo]): Satoshi = Satoshi(utxos.map(_.item.value).sum)

  def totalAmount(utxos: Set[Utxo]): Satoshi = totalAmount(utxos.toSeq)

  /**
    *
    * @param weight transaction weight
    * @param feeRatePerKw fee rate
    * @return the fee for this tx weight
    */
  def computeFee(weight: Int, feeRatePerKw: Long): Satoshi = Satoshi((weight * feeRatePerKw) / 1000)

  /**
    *
    * @param txIn transaction input
    * @return Some(pubkey) if this tx input spends a p2sh-of-p2wpkh(pub), None otherwise
    */
  def extractPubKeySpentFrom(txIn: TxIn) : Option[PublicKey] = {
    Try {
      // we're looking for tx that spend a pay2sh-of-p2wkph output
      require(txIn.witness.stack.size == 2)
      val sig = txIn.witness.stack(0)
      val pub = txIn.witness.stack(1)
      val OP_PUSHDATA(script, _) :: Nil = Script.parse(txIn.signatureScript)
      val publicKey = PublicKey(pub)
      if (Script.write(Script.pay2wpkh(publicKey)) == script) {
        Some(publicKey)
      } else None
    } getOrElse None
  }

  case class Utxo(key: ExtendedPrivateKey, item: ElectrumClient.UnspentItem) {
    def outPoint: OutPoint = item.outPoint
  }

  /**
    * Wallet state, which stores data returned by EletrumX servers.
    * Most items are indexed by script hash (i.e. by pubkey script sha256 hash).
    * Height follow ElectrumX's conventions:
    * - h > 0 means that the tx was confirmed at block #h
    * - 0 means unconfirmed, but all input are confirmed
    * < 0 means unconfirmed, and sonme inputs are unconfirmed as well
    *
    * @param tip current blockchain tip
    * @param accountKeys account keys
    * @param changeKeys change keys
    * @param status script hash -> status; "" means that the script hash has not been used
    *               yet
    * @param transactions wallet transactions
    * @param heights transactions heights
    * @param unspents script hash -> unspents
    * @param history script hash -> history
    * @param locks transactions which lock some of our utxos.
    */
  case class State(tip: ElectrumClient.Header,
                   accountKeys: Vector[ExtendedPrivateKey],
                   changeKeys: Vector[ExtendedPrivateKey],
                   status: Map[BinaryData, String],
                   transactions: Map[BinaryData, Transaction],
                   heights: Map[BinaryData, Long],
                   unspents: Map[BinaryData, Set[ElectrumClient.UnspentItem]],
                   history: Map[BinaryData, Seq[ElectrumClient.TransactionHistoryItem]],
                   locks: Set[Transaction],
                   pendingScriptHashSubscriptionRequests: Set[BinaryData],
                   pendingHistoryRequests: Set[BinaryData],
                   pendingTransactionRequests: Set[BinaryData]) extends Logging {
    lazy val accountKeyMap = accountKeys.map(key => computeScriptHash(key.publicKey) -> key).toMap

    lazy val changeKeyMap = changeKeys.map(key => computeScriptHash(key.publicKey) -> key).toMap

    lazy val unusedAccountKeys = accountKeys.filter(key => status.get(computeScriptHash(key.publicKey)) == Some(""))

    lazy val unusedChangedKeys = changeKeys.filter(key => status.get(computeScriptHash(key.publicKey)) == Some(""))

    lazy val publicScriptMap = (accountKeys ++ changeKeys).map(key => Script.write(computePublicKeyScript(key.publicKey)) -> key).toMap

    lazy val utxos = (unspents.map {
      case (hash, items) =>
        val key = accountKeyMap.getOrElse(hash, changeKeyMap(hash))
        items.map(item => Utxo(key, item))
    }).flatten.toSeq

    def isReady = pendingHistoryRequests.isEmpty && pendingTransactionRequests.isEmpty

    def readyMessage: Ready = {
      val (confirmed, unconfirmed) = balance
      Ready(confirmed, unconfirmed, tip.block_height)
    }

    /**
      *
      * @return the current receive key. In most cases it will be a key that has not
      *         been used yet but it may be possible that we are still looking for
      *         unused keys and none is available yet. In this case we will return
      *         the latest account key.
      */
    def currentReceiveKey = unusedAccountKeys.headOption.getOrElse {
      // bad luck we are still looking for unused keys
      // use the last account key
      accountKeys.last
    }

    def currentReceiveAddress = segwitAddress(currentReceiveKey)

    /**
      *
      * @return the current change key. In most cases it will be a key that has not
      *         been used yet but it may be possible that we are still looking for
      *         unused keys and none is available yet. In this case we will return
      *         the latest change key.
      */
    def currentChangeKey = unusedChangedKeys.headOption.getOrElse {
      // bad luck we are still looking for unused keys
      // use the last account key
      changeKeys.last
    }

    def currentChangeAddress = segwitAddress(currentChangeKey)

    def isMine(txIn: TxIn) : Boolean = {
      extractPubKeySpentFrom(txIn).exists(pub => publicScriptMap.contains(Script.write(computePublicKeyScript(pub))))
    }

    def isSpend(txIn: TxIn, publicKey: PublicKey) : Boolean = {
       extractPubKeySpentFrom(txIn).contains(publicKey)
    }

    /**
      *
      * @param txIn
      * @param scriptHash
      * @return true if txIn spends from an address that matches scriptHash
      */
    def isSpend(txIn: TxIn, scriptHash: BinaryData) : Boolean = {
      extractPubKeySpentFrom(txIn).exists(pub => computeScriptHash(pub) == scriptHash)
    }

    def isReceive(txOut: TxOut, scriptHash: BinaryData) : Boolean = {
      publicScriptMap.get(txOut.publicKeyScript).exists(key => computeScriptHash(key.publicKey) == scriptHash)
    }

    def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

    def computeTransactionDepth(txid: BinaryData) : Long = heights.get(txid).map(height => if (height > 0) tip.block_height - height + 1 else 0).getOrElse(0)
    /**
      *
      * @param scriptHash script hash
      * @return the (confirmed, unconfirmed) balance for this script hash. This balance may not
      *         be up-to-date if we have not received all data we've asked for yet.
      */
    def balance(scriptHash: BinaryData) : (Satoshi, Satoshi) = {
      history.get(scriptHash) match {
        case None => (Satoshi(0), Satoshi(0))

        case Some(items) if items.isEmpty => (Satoshi(0), Satoshi(0))

        case Some(items) =>
          val (confirmedItems, unconfirmedItems) = items.partition(_.height > 0)
          val confirmedTxs = confirmedItems.collect { case item if transactions.contains(BinaryData(item.tx_hash)) => transactions(BinaryData(item.tx_hash)) }
          val unconfirmedTxs = unconfirmedItems.collect { case item if transactions.contains(BinaryData(item.tx_hash)) => transactions(BinaryData(item.tx_hash)) }
          if (confirmedTxs.size + unconfirmedTxs.size < confirmedItems.size + unconfirmedItems.size) logger.warn(s"we have not received all transactions yet, balance will not be up to date")

          def findOurSpentOutputs(txs: Seq[Transaction]): Seq[TxOut] = {
           val inputs = txs.map(_.txIn).flatten.filter(txIn => isSpend(txIn, scriptHash))
            val spentOutputs = inputs.map(_.outPoint).map(outPoint => transactions(outPoint.txid).txOut(outPoint.index.toInt))
            spentOutputs
          }

          val confirmedSpents = findOurSpentOutputs(confirmedTxs)
          val confirmedReceived = confirmedTxs.map(_.txOut).flatten.filter(txOut => isReceive(txOut, scriptHash))

          val unconfirmedSpents = findOurSpentOutputs(unconfirmedTxs)
          val unconfirmedReceived = unconfirmedTxs.map(_.txOut).flatten.filter(txOut => isReceive(txOut, scriptHash))

          val confirmedBalance = confirmedReceived.map(_.amount).sum - confirmedSpents.map(_.amount).sum
          val unconfirmedBalance = unconfirmedReceived.map(_.amount).sum - unconfirmedSpents.map(_.amount).sum

          (confirmedBalance, unconfirmedBalance)
      }
    }

    /**
      *
      * @return the (confirmed, unconfirmed) balance for this wallet. This balance may not
      *         be up-to-date if we have not received all data we've asked for yet.
      */
    lazy val balance: (Satoshi, Satoshi) = {
      (accountKeyMap.keys ++ changeKeyMap.keys).map(scriptHash => balance(scriptHash)).foldLeft((Satoshi(0), Satoshi(0))) {
        case ((confirmed, unconfirmed), (confirmed1, unconfirmed1)) => (confirmed + confirmed1, unconfirmed + unconfirmed1)
      }
    }

    /**
      *
      * @param tx input transaction
      * @return a (received, sent) tuple where sent if what the tx spends from us, and received is what the tx sends to us
      */
    def computeTransactionDelta(tx: Transaction) : (Satoshi, Satoshi) = {
      val mine = tx.txIn.filter(isMine)
      val spent = mine.collect {
        case txIn if transactions.contains(txIn.outPoint.txid) => transactions(txIn.outPoint.txid).txOut(txIn.outPoint.index.toInt)
      }
      if (spent.size != mine.size) logger.warn(s"we don't have all our pending txs yet")
      val received = tx.txOut.filter(isMine)

      (received.map(_.amount).sum, spent.map(_.amount).sum)
    }

    /**
      *
      * @param tx input tx that has no inputs
      * @param feeRatePerKw fee rate per kiloweight
      * @param minimumFee minimi fee
      * @param dustLimit dust limit
      * @return a (state, tx) tuple where state has been updated and tx is a complete,
      *         fully signed transaction that can be broadcast.
      *         our utxos spent by this tx are locked and won't be available for spending
      *         until the tx has been cancelled. If the tx is committed, they will be removed
      */
    def completeTransaction(tx: Transaction, feeRatePerKw: Long, minimumFee: Satoshi, dustLimit: Satoshi) : (State, Transaction) = {
      require(tx.txIn.isEmpty, "cannot complete a tx that already has inputs")
      require(feeRatePerKw >= 0, "Fee rate cannot be negative")
      val amount = tx.txOut.map(_.amount).sum
      require(amount > dustLimit, "amount to send is below dust limit")
      val fee = {
        val estimatedFee = computeFee(700, feeRatePerKw)
        if (estimatedFee < minimumFee) minimumFee else estimatedFee
      }

      @tailrec
      def select(chooseFrom: Seq[Utxo], selected: Set[Utxo]): Set[Utxo] = {
        if (totalAmount(selected) >= amount + fee) selected
        else if (chooseFrom.isEmpty) Set()
        else select(chooseFrom.tail, selected + chooseFrom.head)
      }

      // select utxos that are not locked by pending txs
      val lockedOutputs = locks.map(_.txIn.map(_.outPoint)).flatten
      val unlocked = utxos.filterNot(utxo => lockedOutputs.contains(utxo.outPoint))
      val selected = select(unlocked, Set()).toSeq
      require(totalAmount(selected) >= amount + fee, "insufficient funds")

      // add inputs
      var tx1 = tx.copy(txIn = selected.map(utxo => TxIn(utxo.outPoint, Nil, TxIn.SEQUENCE_FINAL)))

      // add change output
      val change = totalAmount(selected) - amount - fee
      if (change >= dustLimit) tx1 = tx1.addOutput(TxOut(change, computePublicKeyScript(currentChangeKey.publicKey)))

      // sign
      for(i <- 0 until tx1.txIn.size) {
        val key = selected(i).key
        val sig = Transaction.signInput(tx1, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(selected(i).item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
        tx1 = tx1.updateWitness(i, ScriptWitness(sig :: key.publicKey.toBin :: Nil)).updateSigScript(i, OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
      }
      Transaction.correctlySpends(tx1, selected.map(utxo => utxo.outPoint -> TxOut(Satoshi(utxo.item.value), computePublicKeyScript(utxo.key.publicKey))).toMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      val state1 = this.copy(locks = this.locks + tx1)
      (state1, tx1)
    }

    /**
      * unlocks input locked by a pending tx. call this method if the tx will not be used after all
      * @param tx pending transaction
      * @return an updated state
      */
    def cancelTransaction(tx: Transaction) : State = this.copy(locks = this.locks - tx)

    /**
      * remove all our utxos spent by this tx. call this method if the tx was broadcast successfully
      * @param tx pending transaction
      * @return an updated state
      */
    def commitTransaction(tx: Transaction) : State = {
      // find all our outputs spent by this tx
      val spent = tx.txIn.map(_.outPoint)
      val spentUtxos = utxos.filter(utxo => spent.contains(utxo.outPoint))

      var unspents1 = unspents
      spentUtxos.map(utxo => {
        val hash = computeScriptHash(utxo.key.publicKey)
        unspents1 = unspents1.updated(hash, unspents1(hash) - utxo.item)
      })

      val state1 = this.copy(locks = this.locks - tx, transactions = this.transactions + (tx.txid -> tx), heights = this.heights + (tx.txid -> 0L), unspents = unspents1)
      state1
    }
  }

  object State {
    def apply(tip: ElectrumClient.Header, accountKeys: Vector[ExtendedPrivateKey], changeKeys: Vector[ExtendedPrivateKey]): State
    = State(tip, accountKeys, changeKeys, Map(), Map(), Map(), Map(), Map(), Set(), Set(), Set(), Set())
  }

  case class PersistentState(mnemonics: Seq[String], accountKeyCount: Int, changeKeyCount: Int, status: Map[BinaryData, String], transactions: Seq[Transaction])

//  object PersistentState {
//      val persistentStateCoder: Codec[PersistentState] = (
//    ("mnemonics" | listOfN(uint8, String)) ::
//      ("accountKeyCount" | uint16)).as[PersistentState]
//  }

}

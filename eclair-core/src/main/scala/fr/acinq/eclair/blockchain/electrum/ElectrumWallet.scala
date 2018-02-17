package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, derivePrivateKey, hardened}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Block, Crypto, DeterministicWallet, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.rpc.Error
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{GetTransaction, GetTransactionResponse, TransactionHistoryItem, computeScriptHash}
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
  * Simple electrum wallet
  *
  * Typical workflow:
  *
  * client ---- header update ----> wallet
  * client ---- status update ----> wallet
  * client <--- ask history   ----- wallet
  * client ---- history       ----> wallet
  * client <--- ask tx        ----- wallet
  * client ---- tx            ----> wallet
  *
  * @param seed
  * @param client
  * @param params
  */
class ElectrumWallet(seed: BinaryData, client: ActorRef, params: ElectrumWallet.WalletParameters) extends FSM[ElectrumWallet.State, ElectrumWallet.Data] {

  import ElectrumWallet._
  import params._

  val master = DeterministicWallet.generate(seed)

  val accountMaster = accountKey(master)
  val changeMaster = changeKey(master)

  client ! ElectrumClient.AddStatusListener(self)

  // disconnected --> waitingForTip --> running --
  // ^                                            |
  // |                                            |
  //  --------------------------------------------

  /**
    * Send a notification if the wallet is ready and its ready message has not
    * already been sent
    * @param data wallet data
    * @return the input data with an updated 'last ready message' if needed
    */
  def notifyReady(data: ElectrumWallet.Data) : ElectrumWallet.Data = {
    if(data.isReady(swipeRange)) {
      data.lastReadyMessage match {
        case Some(value) if value == data.readyMessage =>
          log.debug(s"ready message $value has already been sent")
          data
        case _ =>
          val ready = data.readyMessage
          log.info(s"wallet is ready with $ready")
          context.system.eventStream.publish(ready)
          context.system.eventStream.publish(NewWalletReceiveAddress(data.currentReceiveAddress))
          data.copy(lastReadyMessage = Some(ready))
      }
    } else data
  }

  startWith(DISCONNECTED, {
    val header = chainHash match {
      case Block.RegtestGenesisBlock.hash => ElectrumClient.Header.RegtestGenesisHeader
      case Block.TestnetGenesisBlock.hash => ElectrumClient.Header.TestnetGenesisHeader
    }
    val firstAccountKeys = (0 until params.swipeRange).map(i => derivePrivateKey(accountMaster, i)).toVector
    val firstChangeKeys = (0 until params.swipeRange).map(i => derivePrivateKey(changeMaster, i)).toVector
    val data = Data(params, header, firstAccountKeys, firstChangeKeys)
    context.system.eventStream.publish(NewWalletReceiveAddress(data.currentReceiveAddress))
    data
  })

  when(DISCONNECTED) {
    case Event(ElectrumClient.ElectrumReady, data) =>
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using data
  }

  when(WAITING_FOR_TIP) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) =>
      data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
      data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHashFromPublicKey(key.publicKey), self))
      // make sure there is not last ready message
      goto(RUNNING) using data.copy(tip = header, lastReadyMessage = None)

    case Event(ElectrumClient.ElectrumDisconnected, data) =>
      log.info(s"wallet got disconnected")
      goto(DISCONNECTED) using data
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) if data.tip == header => stay

    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) =>
      log.info(s"got new tip ${header.block_hash} at ${header.block_height}")
      data.heights.collect {
        case (txid, height) if height > 0 =>
          val confirmations = computeDepth(header.block_height, height)
          context.system.eventStream.publish(TransactionConfidenceChanged(txid, confirmations))
      }
      stay using notifyReady(data.copy(tip = header))

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if data.status.get(scriptHash) == Some(status) =>
      stay using notifyReady(data)// we already have it

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap.contains(scriptHash) =>
      log.warning(s"received status=$status for scriptHash=$scriptHash which does not match any of our keys")
      stay

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if status == "" =>
      val data1 = data.copy(status = data.status + (scriptHash -> status)) // empty status, nothing to do
      stay using notifyReady(data1)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) =>
      val key = data.accountKeyMap.getOrElse(scriptHash, data.changeKeyMap(scriptHash))
      val isChange = data.changeKeyMap.contains(scriptHash)
      log.info(s"received status=$status for scriptHash=$scriptHash key=${segwitAddress(key)} isChange=$isChange")

      // let's retrieve the tx history for this key
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)

      val (newAccountKeys, newChangeKeys) = data.status.get(status) match {
        case None =>
          // first time this script hash is used, need to generate a new key
          val newKey = if (isChange) derivePrivateKey(changeMaster, data.changeKeys.last.path.lastChildNumber + 1) else derivePrivateKey(accountMaster, data.accountKeys.last.path.lastChildNumber + 1)
          val newScriptHash = computeScriptHashFromPublicKey(newKey.publicKey)
          log.info(s"generated key with index=${newKey.path.lastChildNumber} scriptHash=$newScriptHash key=${segwitAddress(newKey)} isChange=$isChange")
          // listens to changes for the newly generated key
          client ! ElectrumClient.ScriptHashSubscription(newScriptHash, self)
          if (isChange) (data.accountKeys, data.changeKeys :+ newKey) else (data.accountKeys :+ newKey, data.changeKeys)
        case Some(_) => (data.accountKeys, data.changeKeys)
      }

      val data1 = data.copy(
        accountKeys = newAccountKeys,
        changeKeys = newChangeKeys,
        status = data.status + (scriptHash -> status),
        pendingHistoryRequests = data.pendingHistoryRequests + scriptHash)

      stay using notifyReady(data1)

    case Event(ElectrumClient.GetScriptHashHistoryResponse(scriptHash, items), data) =>
      log.debug(s"scriptHash=$scriptHash has history=$items")
      val shadow_items = data.history.get(scriptHash) match {
        case Some(existing_items) => existing_items.filterNot(item => items.exists(_.tx_hash == item.tx_hash))
        case None => Nil
      }
      shadow_items.foreach(item => log.warning(s"keeping shadow item for txid=${item.tx_hash}"))
      val items0 = items ++ shadow_items

      val (heights1, pendingTransactionRequests1) = items0.foldLeft((data.heights, data.pendingTransactionRequests)) {
        case ((heights, hashes), item) if !data.transactions.contains(item.tx_hash) && !data.pendingTransactionRequests.contains(item.tx_hash) =>
          // we retrieve the tx if we don't have it and haven't yet requested it
          client ! GetTransaction(item.tx_hash)
          (heights + (item.tx_hash -> item.height), hashes + item.tx_hash)
        case ((heights, hashes), item) =>
          // otherwise we just update the height
          (heights + (item.tx_hash -> item.height), hashes)
      }

      // we now have updated height for all our transactions,
      heights1.collect {
        case (txid, height) =>
          val confirmations = if (height <= 0) 0 else computeDepth(data.tip.block_height, height)
          (data.heights.get(txid), height) match {
            case (None, height) if height <= 0 =>
            // height=0 => unconfirmed, height=-1 => unconfirmed and one input is unconfirmed
            case (None, height) if height > 0 =>
              // first time we get a height for this tx: either it was just confirmed, or we restarted the wallet
              context.system.eventStream.publish(TransactionConfidenceChanged(txid, confirmations))
            case (Some(previousHeight), height) if previousHeight != height =>
              // there was a reorg
              context.system.eventStream.publish(TransactionConfidenceChanged(txid, confirmations))
            case (Some(previousHeight), height) if previousHeight == height =>
            // no reorg, nothing to do
          }
      }
      val data1 = data.copy(heights = heights1, history = data.history + (scriptHash -> items0), pendingHistoryRequests = data.pendingHistoryRequests - scriptHash, pendingTransactionRequests = pendingTransactionRequests1)
      stay using notifyReady(data1)

    case Event(GetTransactionResponse(tx), data) =>
      log.debug(s"received transaction ${tx.txid}")
      data.computeTransactionDelta(tx) match {
        case Some((received, sent, fee_opt)) =>
          log.info(s"successfully connected txid=${tx.txid}")
          context.system.eventStream.publish(TransactionReceived(tx, data.computeTransactionDepth(tx.txid), received, sent, fee_opt))
          // when we have successfully processed a new tx, we retry all pending txes to see if they can be added now
          data.pendingTransactions.foreach(self ! GetTransactionResponse(_))
          val data1 = data.copy(transactions = data.transactions + (tx.txid -> tx), pendingTransactionRequests = data.pendingTransactionRequests - tx.txid, pendingTransactions = Nil)
          stay using notifyReady(data1)
        case None =>
          // missing parents
          log.info(s"couldn't connect txid=${tx.txid}")
          val data1 = data.copy(pendingTransactions = data.pendingTransactions :+ tx)
          stay using notifyReady(data1)
      }

    case Event(CompleteTransaction(tx, feeRatePerKw), data) =>
      Try(data.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, allowSpendUnconfirmed)) match {
        case Success((data1, tx1)) => stay using data1 replying CompleteTransactionResponse(tx1, None)
        case Failure(t) => stay replying CompleteTransactionResponse(tx, Some(t))
      }

    case Event(CommitTransaction(tx), data) =>
      log.info(s"committing txid=${tx.txid}")
      val data1 = data.commitTransaction(tx)
      // we use the initial state to compute the effect of the tx
      // note: we know that computeTransactionDelta and the fee will be defined, because we built the tx ourselves so
      // we know all the parents
      val (received, sent, Some(fee)) = data.computeTransactionDelta(tx).get
      // we notify here because the tx won't be downloaded again (it has been added to the state at commit)
      context.system.eventStream.publish(TransactionReceived(tx, data1.computeTransactionDepth(tx.txid), received, sent, Some(fee)))
      stay using notifyReady(data1) replying CommitTransactionResponse(tx) // goto instead of stay because we want to fire transitions

    case Event(CancelTransaction(tx), data) =>
      log.info(s"cancelling txid=${tx.txid}")
      stay using notifyReady(data.cancelTransaction(tx)) replying CancelTransactionResponse(tx)

    case Event(bc@ElectrumClient.BroadcastTransaction(tx), _) =>
      log.info(s"broadcasting txid=${tx.txid}")
      client forward bc
      stay

    case Event(ElectrumClient.ElectrumDisconnected, data) =>
      log.info(s"wallet got disconnected")
      goto(DISCONNECTED) using data
  }

  whenUnhandled {

    case Event(GetCurrentReceiveAddress, data) => stay replying GetCurrentReceiveAddressResponse(data.currentReceiveAddress)

    case Event(GetBalance, data) =>
      val (confirmed, unconfirmed) = data.balance
      stay replying GetBalanceResponse(confirmed, unconfirmed)

    case Event(GetData, data) => stay replying GetDataResponse(data)

    case Event(ElectrumClient.BroadcastTransaction(tx), _) => stay replying ElectrumClient.BroadcastTransactionResponse(tx, Some(Error(-1, "wallet is not connected")))
  }

  initialize()

}

object ElectrumWallet {

  // use 32 bytes seed, which will generate a 24 words mnemonic code
  val SEED_BYTES_LENGTH = 32

  def props(seed: BinaryData, client: ActorRef, params: WalletParameters): Props = Props(new ElectrumWallet(seed, client, params))

  case class WalletParameters(chainHash: BinaryData, minimumFee: Satoshi = Satoshi(2000), dustLimit: Satoshi = Satoshi(546), swipeRange: Int = 10, allowSpendUnconfirmed: Boolean = true)

  // @formatter:off
  sealed trait State
  case object DISCONNECTED extends State
  case object WAITING_FOR_TIP extends State
  case object RUNNING extends State

  sealed trait Request
  sealed trait Response

  case object GetBalance extends Request
  case class GetBalanceResponse(confirmed: Satoshi, unconfirmed: Satoshi) extends Response

  case object GetCurrentReceiveAddress extends Request
  case class GetCurrentReceiveAddressResponse(address: String) extends Response

  case object GetData extends Request
  case class GetDataResponse(state: Data) extends Response

  case class CompleteTransaction(tx: Transaction, feeRatePerKw: Long) extends Request
  case class CompleteTransactionResponse(tx: Transaction, error: Option[Throwable]) extends Response

  case class CommitTransaction(tx: Transaction) extends Request
  case class CommitTransactionResponse(tx: Transaction) extends Response

  case class SendTransaction(tx: Transaction) extends Request
  case class SendTransactionReponse(tx: Transaction) extends Response

  case class CancelTransaction(tx: Transaction) extends Request
  case class CancelTransactionResponse(tx: Transaction) extends Response

  case object InsufficientFunds extends Response
  case class AmountBelowDustLimit(dustLimit: Satoshi) extends Response

  case class GetPrivateKey(address: String) extends Request
  case class GetPrivateKeyResponse(address: String, key: Option[ExtendedPrivateKey]) extends Response


  sealed trait WalletEvent
  /**
    *
    * @param tx
    * @param depth
    * @param received
    * @param sent
    * @param feeOpt is set only when we know it (i.e. for outgoing transactions)
    */
  case class TransactionReceived(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi, feeOpt: Option[Satoshi]) extends WalletEvent
  case class TransactionConfidenceChanged(txid: BinaryData, depth: Long) extends WalletEvent
  case class NewWalletReceiveAddress(address: String) extends WalletEvent
  case class WalletReady(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long, timestamp: Long) extends WalletEvent
  // @formatter:on

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
  def computeScriptHashFromPublicKey(key: PublicKey): BinaryData = Crypto.sha256(Script.write(computePublicKeyScript(key))).reverse

  /**
    * use BIP49 (and not BIP44) since we use p2sh-of-p2wpkh
    *
    * @param master master key
    * @return the BIP49 account key for this master key: m/49'/1'/0'/0
    */
  def accountKey(master: ExtendedPrivateKey) = DeterministicWallet.derivePrivateKey(master, hardened(49) :: hardened(1) :: hardened(0) :: 0L :: Nil)

  /**
    * use BIP49 (and not BIP44) since we use p2sh-of-p2wpkh
    *
    * @param master master key
    * @return the BIP49 change key for this master key: m/49'/1'/0'/1
    */
  def changeKey(master: ExtendedPrivateKey) = DeterministicWallet.derivePrivateKey(master, hardened(49) :: hardened(1) :: hardened(0) :: 1L :: Nil)

  def totalAmount(utxos: Seq[Utxo]): Satoshi = Satoshi(utxos.map(_.item.value).sum)

  def totalAmount(utxos: Set[Utxo]): Satoshi = totalAmount(utxos.toSeq)

  /**
    *
    * @param weight       transaction weight
    * @param feeRatePerKw fee rate
    * @return the fee for this tx weight
    */
  def computeFee(weight: Int, feeRatePerKw: Long): Satoshi = Satoshi((weight * feeRatePerKw) / 1000)

  /**
    *
    * @param txIn transaction input
    * @return Some(pubkey) if this tx input spends a p2sh-of-p2wpkh(pub), None otherwise
    */
  def extractPubKeySpentFrom(txIn: TxIn): Option[PublicKey] = {
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

  def computeDepth(currentHeight: Long, txHeight: Long): Long = currentHeight - txHeight + 1

  case class Utxo(key: ExtendedPrivateKey, item: ElectrumClient.UnspentItem) {
    def outPoint: OutPoint = item.outPoint
  }

  /**
    * Wallet state, which stores data returned by ElectrumX servers.
    * Most items are indexed by script hash (i.e. by pubkey script sha256 hash).
    * Height follow ElectrumX's conventions:
    * - h > 0 means that the tx was confirmed at block #h
    * - 0 means unconfirmed, but all input are confirmed
    * < 0 means unconfirmed, and some inputs are unconfirmed as well
    *
    * @param tip                        current blockchain tip
    * @param accountKeys                account keys
    * @param changeKeys                 change keys
    * @param status                     script hash -> status; "" means that the script hash has not been used
    *                                   yet
    * @param transactions               wallet transactions
    * @param heights                    transactions heights
    * @param history                    script hash -> history
    * @param locks                      transactions which lock some of our utxos.
    * @param pendingHistoryRequests     requests pending a response from the electrum server
    * @param pendingTransactionRequests requests pending a response from the electrum server
    * @param pendingTransactions        transactions received but not yet connected to their parents
    */
  case class Data(tip: ElectrumClient.Header,
                  accountKeys: Vector[ExtendedPrivateKey],
                  changeKeys: Vector[ExtendedPrivateKey],
                  status: Map[BinaryData, String],
                  transactions: Map[BinaryData, Transaction],
                  heights: Map[BinaryData, Long],
                  history: Map[BinaryData, Seq[ElectrumClient.TransactionHistoryItem]],
                  locks: Set[Transaction],
                  pendingHistoryRequests: Set[BinaryData],
                  pendingTransactionRequests: Set[BinaryData],
                  pendingTransactions: Seq[Transaction],
                  lastReadyMessage: Option[WalletReady]) extends Logging {
    lazy val accountKeyMap = accountKeys.map(key => computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

    lazy val changeKeyMap = changeKeys.map(key => computeScriptHashFromPublicKey(key.publicKey) -> key).toMap

    lazy val firstUnusedAccountKeys = accountKeys.find(key => status.get(computeScriptHashFromPublicKey(key.publicKey)) == Some(""))

    lazy val firstUnusedChangeKeys = changeKeys.find(key => status.get(computeScriptHashFromPublicKey(key.publicKey)) == Some(""))

    lazy val publicScriptMap = (accountKeys ++ changeKeys).map(key => Script.write(computePublicKeyScript(key.publicKey)) -> key).toMap

    lazy val utxos = history.keys.toSeq.map(scriptHash => getUtxos(scriptHash)).flatten

    /**
      * The wallet is ready if all current keys have an empty status, and we don't have
      * any history/tx request pending
      * NB: swipeRange * 2 because we have account keys and change keys
      */
    def isReady(swipeRange: Int) = status.filter(_._2 == "").size >= swipeRange * 2 && pendingHistoryRequests.isEmpty && pendingTransactionRequests.isEmpty

    def readyMessage: WalletReady = {
      val (confirmed, unconfirmed) = balance
      WalletReady(confirmed, unconfirmed, tip.block_height, tip.timestamp)
    }

    /**
      *
      * @return the current receive key. In most cases it will be a key that has not
      *         been used yet but it may be possible that we are still looking for
      *         unused keys and none is available yet. In this case we will return
      *         the latest account key.
      */
    def currentReceiveKey = firstUnusedAccountKeys.headOption.getOrElse {
      // bad luck we are still looking for unused keys
      // use the first account key
      accountKeys.head
    }

    def currentReceiveAddress = segwitAddress(currentReceiveKey)

    /**
      *
      * @return the current change key. In most cases it will be a key that has not
      *         been used yet but it may be possible that we are still looking for
      *         unused keys and none is available yet. In this case we will return
      *         the latest change key.
      */
    def currentChangeKey = firstUnusedChangeKeys.headOption.getOrElse {
      // bad luck we are still looking for unused keys
      // use the first account key
      changeKeys.head
    }

    def currentChangeAddress = segwitAddress(currentChangeKey)

    def isMine(txIn: TxIn): Boolean = extractPubKeySpentFrom(txIn).exists(pub => publicScriptMap.contains(Script.write(computePublicKeyScript(pub))))

    def isSpend(txIn: TxIn, publicKey: PublicKey): Boolean = extractPubKeySpentFrom(txIn).contains(publicKey)

    /**
      *
      * @param txIn
      * @param scriptHash
      * @return true if txIn spends from an address that matches scriptHash
      */
    def isSpend(txIn: TxIn, scriptHash: BinaryData): Boolean = extractPubKeySpentFrom(txIn).exists(pub => computeScriptHashFromPublicKey(pub) == scriptHash)

    def isReceive(txOut: TxOut, scriptHash: BinaryData): Boolean = publicScriptMap.get(txOut.publicKeyScript).exists(key => computeScriptHashFromPublicKey(key.publicKey) == scriptHash)

    def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

    def computeTransactionDepth(txid: BinaryData): Long = heights.get(txid).map(height => if (height > 0) computeDepth(tip.block_height, height) else 0).getOrElse(0)

    /**
      *
      * @param scriptHash script hash
      * @return the list of UTXOs for this script hash (including unconfirmed UTXOs)
      */
    def getUtxos(scriptHash: BinaryData) = {
      history.get(scriptHash) match {
        case None => Seq()
        case Some(items) if items.isEmpty => Seq()
        case Some(items) =>
          // this is the private key for this script hash
          val key = accountKeyMap.getOrElse(scriptHash, changeKeyMap(scriptHash))

          // find all transactions that send to or receive from this script hash
          // we use collect because we may not yet have received all transactions in the history
          val txs = items collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }

          // find all tx outputs that send to our script hash
          val unspents = items collect { case item if transactions.contains(item.tx_hash) =>
            val tx = transactions(item.tx_hash)
            val outputs = tx.txOut.zipWithIndex.filter { case (txOut, index) => isReceive(txOut, scriptHash) }
            outputs.map { case (txOut, index) => Utxo(key, ElectrumClient.UnspentItem(item.tx_hash, index, txOut.amount.toLong, item.height)) }
          } flatten

          // and remove the outputs that are being spent. this is needed because we may have unconfirmed UTXOs
          // that are spend by unconfirmed transactions
          unspents.filterNot(utxo => txs.exists(tx => tx.txIn.exists(_.outPoint == utxo.outPoint)))
      }
    }


    /**
      *
      * @param scriptHash script hash
      * @return the (confirmed, unconfirmed) balance for this script hash. This balance may not
      *         be up-to-date if we have not received all data we've asked for yet.
      */
    def balance(scriptHash: BinaryData): (Satoshi, Satoshi) = {
      history.get(scriptHash) match {
        case None => (Satoshi(0), Satoshi(0))

        case Some(items) if items.isEmpty => (Satoshi(0), Satoshi(0))

        case Some(items) =>
          val (confirmedItems, unconfirmedItems) = items.partition(_.height > 0)
          val confirmedTxs = confirmedItems.collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }
          val unconfirmedTxs = unconfirmedItems.collect { case item if transactions.contains(item.tx_hash) => transactions(item.tx_hash) }
          if (confirmedTxs.size + unconfirmedTxs.size < confirmedItems.size + unconfirmedItems.size) logger.warn(s"we have not received all transactions yet, balance will not be up to date")

          def findOurSpentOutputs(txs: Seq[Transaction]): Seq[TxOut] = {
            val inputs = txs.map(_.txIn).flatten.filter(txIn => isSpend(txIn, scriptHash))
            val spentOutputs = inputs.map(_.outPoint).map(outPoint => transactions.get(outPoint.txid).map(_.txOut(outPoint.index.toInt))).flatten
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
      * Computes the effect of this transaction on the wallet
      *
      * @param tx input transaction
      * @return an option:
      *         - Some(received, sent, fee) where sent if what the tx spends from us, received is what the tx sends to us,
      *         and fee is the fee for the tx) tuple where sent if what the tx spends from us, and received is what the tx sends to us
      *         - None if we are missing one or more parent txs
      */
    def computeTransactionDelta(tx: Transaction): Option[(Satoshi, Satoshi, Option[Satoshi])] = {
      val ourInputs = tx.txIn.filter(isMine)
      // we need to make sure that for all inputs spending an output we control, we already  have the parent tx
      // (otherwise we can't estimate our balance)
      val missingParent = ourInputs.exists(txIn => !transactions.contains(txIn.outPoint.txid))
      if (missingParent) {
        None
      } else {
        val sent = ourInputs.map(txIn => transactions(txIn.outPoint.txid).txOut(txIn.outPoint.index.toInt)).map(_.amount).sum
        val received = tx.txOut.filter(isMine).map(_.amount).sum
        // if all the inputs were ours, we can compute the fee, otherwise we can't
        val fee_opt = if (ourInputs.size == tx.txIn.size) Some(sent - tx.txOut.map(_.amount).sum) else None
        Some((received, sent, fee_opt))
      }
    }

    /**
      *
      * @param tx    input transaction
      * @param utxos input uxtos
      * @return a tx where all utxos have been added as inputs, signed with dummy invalid signatures. This
      *         is used to estimate the weight of the signed transaction
      */
    def addUtxosWithDummySig(tx: Transaction, utxos: Seq[Utxo]): Transaction =
      tx.copy(txIn = utxos.map { case utxo =>
        // we use dummy signature here, because the result is only used to estimate fees
        val sig = BinaryData("01" * 71)
        val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(utxo.key.publicKey))) :: Nil)
        val witness = ScriptWitness(sig :: utxo.key.publicKey.toBin :: Nil)
        TxIn(utxo.outPoint, signatureScript = sigScript, sequence = TxIn.SEQUENCE_FINAL, witness = witness)
      })

    /**
      *
      * @param amount                amount we want to pay
      * @param allowSpendUnconfirmed if true, use unconfirmed utxos
      * @return a set of utxos with a total value that is greater than amount
      */
    def chooseUtxos(amount: Satoshi, allowSpendUnconfirmed: Boolean): Seq[Utxo] = {
      @tailrec
      def select(chooseFrom: Seq[Utxo], selected: Set[Utxo]): Set[Utxo] = {
        if (totalAmount(selected) >= amount) selected
        else if (chooseFrom.isEmpty) throw new IllegalArgumentException("insufficient funds")
        else select(chooseFrom.tail, selected + chooseFrom.head)
      }

      // select utxos that are not locked by pending txs
      val lockedOutputs = locks.map(_.txIn.map(_.outPoint)).flatten
      val unlocked = utxos.filterNot(utxo => lockedOutputs.contains(utxo.outPoint))
      val unlocked1 = if (allowSpendUnconfirmed) unlocked else unlocked.filter(_.item.height > 0)

      // sort utxos by amount, in increasing order
      // this way we minimize the number of utxos in the wallet, and so we minimize the fees we'll pay for them
      val unlocked2 = unlocked1.sortBy(_.item.value)
      val selected = select(unlocked2, Set())
      selected.toSeq
    }

    /**
      *
      * @param tx           input tx that has no inputs
      * @param feeRatePerKw fee rate per kiloweight
      * @param minimumFee   minimum fee
      * @param dustLimit    dust limit
      * @return a (state, tx) tuple where state has been updated and tx is a complete,
      *         fully signed transaction that can be broadcast.
      *         our utxos spent by this tx are locked and won't be available for spending
      *         until the tx has been cancelled. If the tx is committed, they will be removed
      */
    def completeTransaction(tx: Transaction, feeRatePerKw: Long, minimumFee: Satoshi, dustLimit: Satoshi, allowSpendUnconfirmed: Boolean): (Data, Transaction) = {
      require(tx.txIn.isEmpty, "cannot complete a tx that already has inputs")
      require(feeRatePerKw >= 0, "fee rate cannot be negative")
      val amount = tx.txOut.map(_.amount).sum
      require(amount > dustLimit, "amount to send is below dust limit")

      // start with a hefty fee estimate
      val utxos = chooseUtxos(amount + Transactions.weight2fee(feeRatePerKw, 1000), allowSpendUnconfirmed)
      val spent = totalAmount(utxos)

      // add utxos, and sign with dummy sigs
      val tx1 = addUtxosWithDummySig(tx, utxos)

      // compute the actual fee that we should pay
      val fee1 = {
        // add a dummy change output, which will be needed most of the time
        val tx2 = tx1.addOutput(TxOut(amount, computePublicKeyScript(currentChangeKey.publicKey)))
        Transactions.weight2fee(feeRatePerKw, tx2.weight())
      }

      // add change output only if non-dust, otherwise change is added to the fee
      val (tx2, fee2, pos) = (spent - amount - fee1) match {
        case dustChange if dustChange < dustLimit => (tx1, fee1 + dustChange, -1) // if change is below dust we add it to fees
        case change => (tx1.addOutput(TxOut(change, computePublicKeyScript(currentChangeKey.publicKey))), fee1, 1) // change output index is always 1
      }

      // sign our tx
      val tx3 = tx2.copy(txIn = tx2.txIn.zipWithIndex.map { case (txIn, i) =>
        val key = utxos(i).key
        val sig = Transaction.signInput(tx2, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(utxos(i).item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
        val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
        val witness = ScriptWitness(sig :: key.publicKey.toBin :: Nil)
        txIn.copy(signatureScript = sigScript, witness = witness)
      })
      //Transaction.correctlySpends(tx3, utxos.map(utxo => utxo.outPoint -> TxOut(Satoshi(utxo.item.value), computePublicKeyScript(utxo.key.publicKey))).toMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // and add the completed tx to the lokcs
      val data1 = this.copy(locks = this.locks + tx3)

      (data1, tx3)
    }

    /**
      * unlocks input locked by a pending tx. call this method if the tx will not be used after all
      *
      * @param tx pending transaction
      * @return an updated state
      */
    def cancelTransaction(tx: Transaction): Data = this.copy(locks = this.locks - tx)

    /**
      * remove all our utxos spent by this tx. call this method if the tx was broadcast successfully
      *
      * @param tx pending transaction
      * @return an updated state
      */
    def commitTransaction(tx: Transaction): Data = {
      // HACK! since we base our utxos computation on the history as seen by the electrum server (so that it is
      // reorg-proof out of the box), we need to update the history  right away if we want to be able to build chained
      // unconfirmed transactions. A few seconds later electrum will notify us and the entry will be overwritten.
      // Note that we need to take into account both inputs and outputs, because there may be change.
      val history1 = (tx.txIn.filter(isMine).map(extractPubKeySpentFrom).flatten.map(computeScriptHashFromPublicKey) ++ tx.txOut.filter(isMine).map(_.publicKeyScript).map(computeScriptHash))
        .foldLeft(this.history) {
          case (history, scriptHash) =>
            val entry = history.get(scriptHash) match {
              case None => Seq(TransactionHistoryItem(0, tx.txid))
              case Some(items) if items.map(_.tx_hash).contains(tx.txid) => items
              case Some(items) => items :+ TransactionHistoryItem(0, tx.txid)
            }
            history + (scriptHash -> entry)
        }
      this.copy(locks = this.locks - tx, transactions = this.transactions + (tx.txid -> tx), heights = this.heights + (tx.txid -> 0L), history = history1)
    }
  }

  object Data {
    def apply(params: ElectrumWallet.WalletParameters, tip: ElectrumClient.Header, accountKeys: Vector[ExtendedPrivateKey], changeKeys: Vector[ExtendedPrivateKey]): Data
    = Data(tip, accountKeys, changeKeys, Map(), Map(), Map(), Map(), Set(), Set(), Set(), Seq(), None)
  }

  case class InfiniteLoopException(data: Data, tx: Transaction) extends Exception

}

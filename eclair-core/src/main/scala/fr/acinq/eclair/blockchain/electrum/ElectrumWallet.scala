package fr.acinq.eclair.blockchain.electrum

import java.io.File

import akka.actor.{ActorRef, LoggingFSM, Props, Terminated}
import com.google.common.io.Files
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, derivePrivateKey, hardened}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Block, Crypto, DeterministicWallet, MnemonicCode, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{GetTransaction, GetTransactionResponse, TransactionHistoryItem}
import fr.acinq.eclair.randomBytes
import grizzled.slf4j.Logging

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

class ElectrumWallet(mnemonics: Seq[String], client: ActorRef, params: ElectrumWallet.WalletParameters) extends LoggingFSM[ElectrumWallet.State, ElectrumWallet.Data] {

  import ElectrumWallet._
  import params._

  val header = chainHash match {
    case Block.RegtestGenesisBlock.hash => ElectrumClient.Header.RegtestGenesisHeader
    case Block.TestnetGenesisBlock.hash => ElectrumClient.Header.TestnetGenesisHeader
  }

  val seed = MnemonicCode.toSeed(mnemonics, "")
  val master = DeterministicWallet.generate(seed)

  val accountMaster = accountKey(master)
  val changeMaster = changeKey(master)

  client ! ElectrumClient.AddStatusListener(self)
  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  // disconnected --> waitingForTip --> running --
  // ^                                            |
  // |                                            |
  //  --------------------------------------------

  startWith(DISCONNECTED, {
    val firstAccountKeys = (0 until params.swipeRange).map(i => derivePrivateKey(accountMaster, i)).toVector
    val firstChangeKeys = (0 until params.swipeRange).map(i => derivePrivateKey(changeMaster, i)).toVector
    Data(params, header, firstAccountKeys, firstChangeKeys)
  })

  when(DISCONNECTED) {
    case Event(ElectrumClient.Ready, data) =>
      client ! ElectrumClient.HeaderSubscription(self)
      goto(WAITING_FOR_TIP) using data
  }

  when(WAITING_FOR_TIP) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) =>
      data.accountKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHash(key.publicKey), self))
      data.changeKeys.foreach(key => client ! ElectrumClient.ScriptHashSubscription(computeScriptHash(key.publicKey), self))
      goto(RUNNING) using data.copy(tip = header)

    case Event(ElectrumClient.Disconnected, data) =>
      log.info(s"wallet got disconnected")
      goto(DISCONNECTED) using data
  }

  when(RUNNING) {
    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) if data.tip == header => stay

    case Event(ElectrumClient.HeaderSubscriptionResponse(header), data) =>
      log.info(s"got new tip ${header.block_hash} at ${header.block_height}")
      data.heights.collect {
        case (txid, height) if height > 0 => statusListeners.map(_ ! WalletTransactionConfidenceChanged(txid, header.block_height - height + 1))
      }
      stay using data.copy(tip = header)

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if data.status.get(scriptHash) == Some(status) => stay // we already have it

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if !data.accountKeyMap.contains(scriptHash) && !data.changeKeyMap.contains(scriptHash) =>
      log.warning(s"received status=$status for scriptHash=$scriptHash which does not match any of our keys")
      stay

    case Event(ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status), data) if status == "" => stay using data.copy(status = data.status + (scriptHash -> status)) // empty status, nothing to do

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
          val newScriptHash = computeScriptHash(newKey.publicKey)
          log.info(s"generated key with index=${key.path.lastChildNumber} scriptHash=$newScriptHash key=${segwitAddress(key)} isChange=$isChange")
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

      goto(stateName) using data1 // goto instead of stay because we want to fire transitions

    case Event(ElectrumClient.GetScriptHashHistoryResponse(scriptHash, history), data) =>
      log.debug(s"scriptHash=$scriptHash has history=$history")
      val (heights1, pendingTransactionRequests1) = history.foldLeft((data.heights, data.pendingTransactionRequests)) {
        case ((heights, hashes), item) if !data.transactions.contains(item.tx_hash) && !data.pendingTransactionRequests.contains(item.tx_hash) =>
          // we retrieve the tx if we don't have it and haven't yet requested it
          client ! GetTransaction(item.tx_hash)
          (heights + (item.tx_hash -> item.height), hashes + item.tx_hash)
        case ((heights, hashes), item) =>
          // otherwise we just update the height
          (heights + (item.tx_hash -> item.height), hashes)
      }
      val data1 = data.copy(heights = heights1, history = data.history + (scriptHash -> history), pendingHistoryRequests = data.pendingHistoryRequests - scriptHash, pendingTransactionRequests = pendingTransactionRequests1)
      goto(stateName) using data1 // goto instead of stay because we want to fire transitions

    case Event(GetTransactionResponse(tx), data) =>
      log.debug(s"received transaction ${tx.txid}")
      val (received, sent) = data.computeTransactionDelta(tx)
      statusListeners.map(_ ! WalletTransactionReceive(tx, data.computeTransactionDepth(tx.txid), received, sent))
      val data1 = data.copy(transactions = data.transactions + (tx.txid -> tx), pendingTransactionRequests = data.pendingTransactionRequests - tx.txid)
      goto(stateName) using data1 // goto instead of stay because we want to fire transitions

    case Event(CompleteTransaction(tx, feeRatePerKw), data) =>
      Try(data.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, allowSpendUnconfirmed)) match {
        case Success((data1, tx1)) => stay using data1 replying CompleteTransactionResponse(tx1, None)
        case Failure(t) => stay replying CompleteTransactionResponse(tx, Some(t))
      }

    case Event(CommitTransaction(tx), data) =>
      val data1 = data.commitTransaction(tx)
      val (received, sent) = data.computeTransactionDelta(tx) // we use the initial state to compute the effect of the tx
      // we notify here because the tx won't be downloaded again (it has been added to the state at commit)
      statusListeners.map(_ ! WalletTransactionReceive(tx, data1.computeTransactionDepth(tx.txid), received, sent))
      goto(stateName) using data1 replying CommitTransactionResponse(tx) // goto instead of stay because we want to fire transitions

    case Event(CancelTransaction(tx), data) =>
      stay using data.cancelTransaction(tx) replying CancelTransactionResponse(tx)

    case Event(bc@ElectrumClient.BroadcastTransaction(tx), _) =>
      log.info(s"broadcasting txid=${tx.txid}")
      client forward bc
      stay

    case Event(ElectrumClient.Disconnected, data) =>
      log.info(s"wallet got disconnected")
      goto(DISCONNECTED) using data
  }

  whenUnhandled {
    case Event(GetMnemonicCode, _) => stay replying GetMnemonicCodeResponse(mnemonics)

    case Event(GetCurrentReceiveAddress, data) => stay replying GetCurrentReceiveAddressResponse(data.currentReceiveAddress)

    case Event(GetBalance, data) =>
      val (confirmed, unconfirmed) = data.balance
      stay replying GetBalanceResponse(confirmed, unconfirmed)

    case Event(GetData, data) => stay replying GetDataResponse(data)

    case Event(ElectrumClient.BroadcastTransaction(tx), _) => stay replying ElectrumClient.BroadcastTransactionResponse(tx, Some("wallet is not connected"))

    case Event(ElectrumClient.AddStatusListener(actor), _) =>
      context.watch(actor)
      statusListeners += actor
      stay

    case Event(Terminated(actor), _) =>
      statusListeners -= actor
      stay
  }

  onTransition {
    case _ -> _ if nextStateData.isReady(params.swipeRange) =>
      val ready = nextStateData.readyMessage
      log.info(s"wallet is ready with $ready")
      statusListeners.map(_ ! ready)
  }

  initialize()

}

object ElectrumWallet {

  def props(mnemonics: Seq[String], client: ActorRef, params: WalletParameters): Props = Props(new ElectrumWallet(mnemonics, client, params))

  def props(file: File, client: ActorRef, params: WalletParameters): Props = {
    val entropy: BinaryData = (file.exists(), file.canRead(), file.isFile) match {
      case (true, true, true) => Files.toByteArray(file)
      case (false, _, _) =>
        val buffer = randomBytes(16)
        Files.write(buffer, file)
        buffer
      case _ => throw new IllegalArgumentException(s"cannot create wallet:$file exist but cannot read from")
    }
    val mnemonics = MnemonicCode.toMnemonics(entropy)
    Props(new ElectrumWallet(mnemonics, client, params))
  }

  case class WalletParameters(chainHash: BinaryData, minimumFee: Satoshi = Satoshi(2000), dustLimit: Satoshi = Satoshi(546), swipeRange: Int = 10, allowSpendUnconfirmed: Boolean = true)

  // @formatter:off
  sealed trait State
  case object DISCONNECTED extends State
  case object WAITING_FOR_TIP extends State
  case object RUNNING extends State

  sealed trait Request
  sealed trait Response

  case object GetMnemonicCode extends RuntimeException
  case class GetMnemonicCodeResponse(mnemonics: Seq[String]) extends Response

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

  case class WalletTransactionReceive(tx: Transaction, depth: Long, received: Satoshi, sent: Satoshi)
  case class WalletTransactionConfidenceChanged(txid: BinaryData, depth: Long)

  case class Ready(confirmedBalance: Satoshi, unconfirmedBalance: Satoshi, height: Long)
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
  def computeScriptHash(key: PublicKey): BinaryData = Crypto.sha256(Script.write(computePublicKeyScript(key))).reverse

  /**
    *
    * @param publicKeyScript public key script
    * @return the hash of the public key script, as used by ElectrumX's hash-based methods
    */
  def computeScriptHash(publicKeyScript: BinaryData): BinaryData = Crypto.sha256(publicKeyScript).reverse

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
    * @param tip          current blockchain tip
    * @param accountKeys  account keys
    * @param changeKeys   change keys
    * @param status       script hash -> status; "" means that the script hash has not been used
    *                     yet
    * @param transactions wallet transactions
    * @param heights      transactions heights
    * @param history      script hash -> history
    * @param locks        transactions which lock some of our utxos.
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
                  pendingTransactionRequests: Set[BinaryData]) extends Logging {
    lazy val accountKeyMap = accountKeys.map(key => computeScriptHash(key.publicKey) -> key).toMap

    lazy val changeKeyMap = changeKeys.map(key => computeScriptHash(key.publicKey) -> key).toMap

    lazy val firstUnusedAccountKeys = accountKeys.find(key => status.get(computeScriptHash(key.publicKey)) == Some(""))

    lazy val firstUnusedChangeKeys = changeKeys.find(key => status.get(computeScriptHash(key.publicKey)) == Some(""))

    lazy val publicScriptMap = (accountKeys ++ changeKeys).map(key => Script.write(computePublicKeyScript(key.publicKey)) -> key).toMap

    lazy val utxos = history.keys.toSeq.map(scriptHash => getUtxos(scriptHash)).flatten

    /**
      * The wallet is ready if all current keys have an empty status, and we don't have
      * any history/tx request pending
      */
    def isReady(swipeRange: Int) = status.filter(_._2 == "").size >= swipeRange && pendingHistoryRequests.isEmpty && pendingTransactionRequests.isEmpty

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
    def currentReceiveKey = firstUnusedAccountKeys.headOption.getOrElse {
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
    def currentChangeKey = firstUnusedChangeKeys.headOption.getOrElse {
      // bad luck we are still looking for unused keys
      // use the last account key
      changeKeys.last
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
    def isSpend(txIn: TxIn, scriptHash: BinaryData): Boolean = extractPubKeySpentFrom(txIn).exists(pub => computeScriptHash(pub) == scriptHash)

    def isReceive(txOut: TxOut, scriptHash: BinaryData): Boolean = publicScriptMap.get(txOut.publicKeyScript).exists(key => computeScriptHash(key.publicKey) == scriptHash)

    def isMine(txOut: TxOut): Boolean = publicScriptMap.contains(txOut.publicKeyScript)

    def computeTransactionDepth(txid: BinaryData): Long = heights.get(txid).map(height => if (height > 0) tip.block_height - height + 1 else 0).getOrElse(0)

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
      * @return a (received, sent) tuple where sent if what the tx spends from us, and received is what the tx sends to us
      */
    def computeTransactionDelta(tx: Transaction): (Satoshi, Satoshi) = {
      val spent = tx.txIn.filter(isMine).flatMap {
        case txIn if transactions.contains(txIn.outPoint.txid) => Some(transactions(txIn.outPoint.txid).txOut(txIn.outPoint.index.toInt))
        case txIn =>
          logger.info(s"tx spends our output but we don't have the parent yet txid=${tx.txid} parentTxId=${txIn.outPoint.txid}")
          None
      }
      val received = tx.txOut.filter(isMine)
      (received.map(_.amount).sum, spent.map(_.amount).sum)
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
      val unlocked1 = if (allowSpendUnconfirmed) unlocked else unlocked.filter(_.item.height > 0)
      val selected = select(unlocked1, Set()).toSeq
      require(totalAmount(selected) >= amount + fee, "insufficient funds")

      // add inputs
      var tx1 = tx.copy(txIn = selected.map(utxo => TxIn(utxo.outPoint, Nil, TxIn.SEQUENCE_FINAL)))

      // add change output
      val change = totalAmount(selected) - amount - fee
      if (change >= dustLimit) tx1 = tx1.addOutput(TxOut(change, computePublicKeyScript(currentChangeKey.publicKey)))

      // sign
      for (i <- 0 until tx1.txIn.size) {
        val key = selected(i).key
        val sig = Transaction.signInput(tx1, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(selected(i).item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
        tx1 = tx1.updateWitness(i, ScriptWitness(sig :: key.publicKey.toBin :: Nil)).updateSigScript(i, OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
      }
      Transaction.correctlySpends(tx1, selected.map(utxo => utxo.outPoint -> TxOut(Satoshi(utxo.item.value), computePublicKeyScript(utxo.key.publicKey))).toMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      val data1 = this.copy(locks = this.locks + tx1)
      (data1, tx1)
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
      val history1 = (tx.txIn.filter(isMine).map(extractPubKeySpentFrom).flatten.map(computeScriptHash) ++ tx.txOut.filter(isMine).map(_.publicKeyScript).map(computeScriptHash))
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
    = Data(tip, accountKeys, changeKeys, Map(), Map(), Map(), Map(), Set(), Set(), Set())
  }

}

package fr.acinq.eclair.blockchain.electrum

import java.io.File

import com.google.common.io.Files
import java.security.SecureRandom

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, hardened}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Crypto, DeterministicWallet, OutPoint, Satoshi, Script, _}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{GetTransaction, UnspentItem}
import grizzled.slf4j.Logging

import scala.annotation.tailrec
import scala.util.Try

class ElectrumWallet(mnemonics: Seq[String], client: ActorRef) extends Actor with Stash with ActorLogging {

  import DeterministicWallet._
  import ElectrumWallet._

  val seed = MnemonicCode.toSeed(mnemonics, "")
  val master = DeterministicWallet.generate(seed)

  client ! ElectrumClient.AddStatusListener(self)

  val swipeRange = 10
  val dustLimit = 546 satoshi
  val feeRatePerKw = 10000
  val minimumFee = Satoshi(1000)

  val accountMaster = accountKey(master)
  val accountIndex = 0

  val changeMaster = changeKey(master)
  val changeIndex = 0

  val firstAccountKeys = (0 until 10).map(i => derivePrivateKey(accountMaster, i)).toVector
  val firstChangeKeys = (0 until 10).map(i => derivePrivateKey(changeMaster, i)).toVector

  val statusListeners = collection.mutable.HashSet.empty[ActorRef]

  def receive = disconnected(State(firstAccountKeys, firstChangeKeys, Set(), Map(), Map(), Map()))

  def disconnected(state: State): Receive = {
    case ElectrumClient.Ready =>
      (state.accountKeys ++ state.changeKeys).map(key => {
        client ! ElectrumClient.ScriptHashSubscription(scriptHash(key.publicKey), self)
      })
      context become running(state)

    case GetState => sender ! GetStateResponse(state)

    case GetCurrentReceiveAddress => sender ! GetCurrentReceiveAddressResponse(state.currentReceiveAddress)
  }

  def running(state: State): Receive = {
    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) if state.status.get(scriptHash) == Some(status) => log.debug(s"$scriptHash is already up to date")

    case ElectrumClient.ScriptHashSubscriptionResponse(scriptHash, status) =>
      log.debug(s"$scriptHash has a new status $status")
      client ! ElectrumClient.ScriptHashListUnspent(scriptHash)
      client ! ElectrumClient.GetScriptHashHistory(scriptHash)
      val state1 = state.copy(status = state.status + (scriptHash -> status))

      // check whether we have status info for all our keys
      if (state1.status.size == state1.accountKeys.size + state1.changeKeys.size) {
        log.info(s"swipe complete")
        val state2 = if (state1.unusedAccountKeys.size < swipeRange) {
          log.info(s"generating new account keys")
          val start = state1.accountKeys.last.path.lastChildNumber + 1
          val end = start + swipeRange - state1.unusedAccountKeys.size
          val newkeys = (start until end).map(i => derivePrivateKey(accountMaster, i)).toVector
          newkeys.map(key => {
            client ! ElectrumClient.ScriptHashSubscription(ElectrumWallet.scriptHash(key.publicKey), self)
          })
          state1.copy(accountKeys = state1.accountKeys ++ newkeys)
        } else state1
        val state3 = if (state2.unusedChangedKeys.size < swipeRange) {
          log.info(s"generating new change keys")
          val start = state2.changeKeys.last.path.lastChildNumber + 1
          val end = start + swipeRange - state2.unusedChangedKeys.size
          val newkeys = (start until end).map(i => derivePrivateKey(changeMaster, i)).toVector
          newkeys.map(key => {
            client ! ElectrumClient.ScriptHashSubscription(ElectrumWallet.scriptHash(key.publicKey), self)
          })
          state2.copy(changeKeys = state2.changeKeys ++ newkeys)
        } else state2
        context become running(state3)
      } else {
        context become running(state1)
      }

    case resp@ElectrumClient.ScriptHashListUnspentResponse(scriptHash, unspents)  =>
      unspents.filterNot(item => state.transactions.contains(BinaryData(item.tx_hash))).map(item => client ! ElectrumClient.GetTransaction(item.tx_hash))
      val state1 = state.updateUtxos(resp)
      if (state1.utxos != state.utxos) log.debug(s"balance before ${state.balance} now: ${state1.balance}")
      context become running(state1)

    case ElectrumClient.GetScriptHashHistoryResponse(scriptHash, history) =>
     log.debug(s"history for script hash $scriptHash: $history")
      var heights1 = state.heights
      history.map(item => {
        if (!state.transactions.contains(BinaryData(item.tx_hash))) client ! GetTransaction(item.tx_hash)
        statusListeners.map(_ ! WalletTransactionConfidenceChanged(BinaryData(item.tx_hash), item.height))
        heights1 = heights1 + (BinaryData(item.tx_hash) -> item.height)
      })
      context become running(state.copy(heights = heights1))

    case ElectrumClient.GetTransactionResponse(tx) if state.transactions.contains(tx.txid) => ()

    case ElectrumClient.GetTransactionResponse(tx) =>
      log.debug(s"received new wallet tx ${tx.txid}")
      val state1 = state.addTransaction(tx)

      statusListeners.map(_ ! WalletTransactionReceive(tx, state.balance, state1.balance))
      statusListeners.map(_ ! WalletTransactionConfidenceChanged(tx.txid, state.heights(tx.txid)))
      context become running(state1)

    case CompleteTransaction(tx) =>
      try {
        val (state1, tx1) = state.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)
        sender ! CompleteTransactionResponse(tx1, None)
        context become running(state1)
      }
      catch {
        case t: Throwable => sender ! CompleteTransactionResponse(tx, Some(t))
      }

    case CommitTransaction(tx) =>
      try {
        val state1 = state.commitTransaction(tx)
        sender ! CommitTransactionResponse(tx, None)
        context become running(state1)
      }
      catch {
        case t: Throwable => sender ! CommitTransactionResponse(tx, Some(t))
      }

    case bc@ElectrumClient.BroadcastTransaction(tx) =>
      val replyTo = sender()
      client ! bc
      context become {
        case resp@ElectrumClient.BroadcastTransactionResponse(_, None) =>
          replyTo ! resp
          unstashAll()
          context become running(state.commitTransaction(tx))
        case resp@ElectrumClient.BroadcastTransactionResponse(_, Some(error)) =>
          log.error(s"cannot broadcast tx ${tx.txid}: $error")
          replyTo ! resp
          unstashAll()
          context become running(state.cancelTransaction(tx))
        case _ => stash()
      }

    case GetBalance => sender ! GetBalanceResponse(state.balance)

    case GetCurrentReceiveAddress => sender ! GetCurrentReceiveAddressResponse(state.currentReceiveAddress)

    case GetState => sender ! GetStateResponse(state)

    case ElectrumClient.Disconnected =>
      log.info("wallet got disconnected")
      statusListeners.map(_ ! ElectrumClient.Disconnected)
      context become disconnected(state)
  }

  override def unhandled(message: Any): Unit = {
    message match {
      case GetMnemonicCode => sender ! GetMnemonicCodeResponse(mnemonics)
      case ElectrumClient.AddStatusListener(actor) if statusListeners.contains(actor) => ()
      case ElectrumClient.AddStatusListener(actor) =>
        context.watch(actor)
        statusListeners += actor
      case Terminated(actor) =>
        statusListeners -= actor
      case _ => log.warning(s"unhandled $message")
    }
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    log.warning(s"preRestart($reason, $message")
  }
}

object ElectrumWallet {

  def props(mnemonics: Seq[String], client: ActorRef): Props = {
    val seed = MnemonicCode.toSeed(mnemonics, "")
    Props(new ElectrumWallet(mnemonics, client))
  }

  def props(file: File, client: ActorRef): Props = {
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
    Props(new ElectrumWallet(mnemonics, client))
  }

  // @formatter:off
  sealed trait Request
  sealed trait Response

  case object GetMnemonicCode extends RuntimeException
  case class GetMnemonicCodeResponse(mnemonics: Seq[String]) extends Response

  case object GetBalance extends Request
  case class GetBalanceResponse(balance: Satoshi) extends Response

  case object GetCurrentReceiveAddress extends Request
  case class GetCurrentReceiveAddressResponse(address: String) extends Response

  case object GetState extends Request
  case class GetStateResponse(state: ElectrumWallet.State) extends Response

  case class CompleteTransaction(tx: Transaction) extends Request
  case class CompleteTransactionResponse(tx: Transaction, error: Option[Throwable]) extends Response

  case class CommitTransaction(tx: Transaction) extends Request
  case class CommitTransactionResponse(tx: Transaction, error: Option[Throwable]) extends Response

  case class SendTransaction(tx: Transaction) extends Request
  case class SendTransactionReponse(tx: Transaction) extends Response

  case class CancelTransaction(tx: Transaction) extends Request
  case class CancelTransactionResponse(tx: Transaction) extends Response

  case object InsufficientFunds extends Response
  case class AmountBelowDustLimit(dustLimit: Satoshi) extends Response

  case class WalletTransactionReceive(tx: Transaction, spent: Satoshi, received: Satoshi)
  case class WalletTransactionConfidenceChanged(txid: BinaryData, depth: Long)

  case class Utxo(outPoint: OutPoint, amount: Satoshi, key: PrivateKey, locked: Boolean)
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
  def publicKeyScript(key: PublicKey) = Script.pay2sh(Script.pay2wpkh(key))

  /**
    *
    * @param key public key
    * @return the hash of the public key script for this key, as used by ElectrumX's hash-based methods
    */
  def scriptHash(key: PublicKey): BinaryData = Crypto.sha256(Script.write(publicKeyScript(key))).reverse

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

  def totalAmount(utxos: Seq[Utxo]): Satoshi = utxos.map(_.amount).sum

  def totalAmount(utxos: Set[Utxo]): Satoshi = totalAmount(utxos.toSeq)

  /**
    * Select utxos to pay a given amount with a minimum fee. Locked utxos are not used.
    * @param utxos utxo set
    * @param amount  amount to pay
    * @param miniminumFe minimum fee to pay
    * @return a list of utxo, which is empty if selection failed because there is not
    *         enough money in the utxo set
    */
  def selectUtxos(utxos: Set[Utxo], amount: Satoshi, miniminumFe: Satoshi): Seq[Utxo] = {

    @tailrec
    def loop(utxos: Seq[Utxo], acc: Set[Utxo] = Set()): Set[Utxo] = {
      if (totalAmount(acc) >= amount + miniminumFe) acc
      else if (utxos.isEmpty) Set()
      else loop(utxos.tail, acc + utxos.head)
    }

    loop(utxos.filter(_.locked == false).toSeq.sortBy(_.amount)).toSeq
  }

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

  /**
    *
    * @param accountKeys wallet account keys
    * @param changeKeys wallet change keys
    * @param utxos current utxo set
    * @param status
    * @param transactions wallet transactions (i.e. that send to or receive money from this wallet)
    * @param heights height at which the wallet transactions were published
    */
  case class State(accountKeys: Vector[ExtendedPrivateKey],
                   changeKeys: Vector[ExtendedPrivateKey],
                   utxos: Set[Utxo],
                   status: Map[BinaryData, String],
                   transactions: Map[BinaryData, Transaction],
                   heights: Map[BinaryData, Long]) extends Logging {
    lazy val accountKeyMap = accountKeys.map(key => scriptHash(key.publicKey) -> key).toMap

    lazy val changeKeyMap = changeKeys.map(key => scriptHash(key.publicKey) -> key).toMap

    lazy val balance = totalAmount(utxos)

    lazy val unusedAccountKeys = accountKeys.filter(key => status.get(scriptHash(key.publicKey)) == Some(""))

    lazy val unusedChangedKeys = changeKeys.filter(key => status.get(scriptHash(key.publicKey)) == Some(""))

    lazy val publicScriptMap = (accountKeys ++ changeKeys).map(key => Script.write(publicKeyScript(key.publicKey)) -> key).toMap

    def updateUtxos(unspents: ElectrumClient.ScriptHashListUnspentResponse) : State = {
      if (!accountKeyMap.contains(unspents.scriptHash) && !changeKeyMap.contains(unspents.scriptHash)) {
        this
      }
      else {
        val key = accountKeyMap.get(unspents.scriptHash) match {
          case Some(value) =>
            logger.debug(s"unspent for account address ${segwitAddress(value)} script ${unspents.scriptHash}: $unspents")
            value
          case None =>
            val value = changeKeyMap(unspents.scriptHash)
            logger.debug(s"unspent for change address ${segwitAddress(value)} script ${unspents.scriptHash}: $unspents")
            value
        }

        val utxosForAddress = utxos.filter(_.key == key.privateKey)
        val newUtxos = unspents.unspents.map(item => {
          Utxo(OutPoint(BinaryData(item.tx_hash).reverse, item.tx_pos), Satoshi(item.value), key.privateKey, false)
        })
        val utxos1 = (utxos -- utxosForAddress) ++ newUtxos
        var heights1 = heights
        unspents.unspents.map(item => {
          heights1 = heights1 + (BinaryData(item.tx_hash) -> item.height)
        })
        this.copy(utxos = utxos1, heights = heights1)
      }
    }

    def addTransaction(tx: Transaction) : State = {
      // find out what it spends form us
      val spentPubKeys = tx.txIn.map(extractPubKeySpentFrom).flatten
      val spentUtxos = utxos.filter(utxo => spentPubKeys.contains(utxo.key.publicKey))

      // and what it sends to us
      val newUtxos = for (i <- 0 until tx.txOut.size) yield publicScriptMap.get(tx.txOut(i).publicKeyScript).map(key => Utxo(OutPoint(tx, i), tx.txOut(i).amount, key.privateKey, false))

      val utxos1 = (utxos -- spentUtxos) ++ newUtxos.flatten
      this.copy(transactions = this.transactions + (tx.txid -> tx), utxos = utxos1)
    }

    def addTransaction(tx: String) : State = addTransaction(Transaction.read(tx))

    /**
      *
      * @param tx transaction to complete; must have no inputs
      * @param feeRatePerKw fee rate
      * @param minimumFee minimum fee
      * @param dustLimit dust limit
      * @return a (`state`, `transaction`) tuple where `state` has been updated (all utxos used by `tx`
      *         are maked as locked) and `tx` is a complete, fully-signed transaction
      */
    def completeTransaction(tx: Transaction, feeRatePerKw: Long, minimumFee: Satoshi, dustLimit: Satoshi): (State, Transaction) = {
      require(tx.txIn.isEmpty, "cannot complete a tx that already has inputs")
      val amount = tx.txOut.map(_.amount).sum
      val fee = {
        val estimatedFee = computeFee(500, feeRatePerKw)
        if (estimatedFee < minimumFee) minimumFee else estimatedFee
      }
      val toSpend = selectUtxos(utxos, amount, fee)
      require(totalAmount(toSpend) > amount + fee, "insufficient funds")
      var tx1 = tx.copy(txIn = toSpend.map(utxo => TxIn(utxo.outPoint, Nil, TxIn.SEQUENCE_FINAL)))
      val change = totalAmount(toSpend) - amount - fee
      if (change > dustLimit) {
        tx1 = tx1.copy(txOut = tx.txOut :+ TxOut(change, publicKeyScript(currentChangeKey.publicKey)))
      }
      for (i <- 0 until tx1.txIn.length) {
        val key = toSpend(i).key
        val sig = Transaction.signInput(tx1, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, toSpend(i).amount, SigVersion.SIGVERSION_WITNESS_V0, key)
        tx1 = tx1.updateWitness(i, ScriptWitness(sig :: key.publicKey.toBin :: Nil)).updateSigScript(i, OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
      }
      Transaction.correctlySpends(tx1, toSpend.map(utxo => utxo.outPoint -> TxOut(utxo.amount, publicKeyScript(utxo.key.publicKey))).toMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      val utxos1 = (utxos -- toSpend) ++ toSpend.map(_.copy(locked = true))
      val state1 = this.copy(utxos = utxos1)
      (state1, tx1)
    }

    /**
      *
      * @param tx transaction
      * @return an update state where all utxos locked by this tx have been unlocked
      */
    def cancelTransaction(tx: Transaction): State = {
      val outPoints = tx.txIn.map(_.outPoint)
      val utxos1 = utxos.map(utxo => if (outPoints.contains(utxo.outPoint)) utxo.copy(locked = false) else utxo)
      this.copy(utxos = utxos1)
    }

    /**
      *
      * @param tx transaction
      * @return an update state where all utxos locked by this tx have been removed
      */
    def commitTransaction(tx: Transaction): State = {
      addTransaction(tx)
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
  }

}

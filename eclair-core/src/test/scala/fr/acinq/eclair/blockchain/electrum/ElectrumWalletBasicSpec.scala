package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.Try

@RunWith(classOf[JUnitRunner])
class ElectrumWalletBasicSpec extends FunSuite {
  import ElectrumWallet._

  val swipeRange = 10
  val dustLimit = 546 satoshi
  val feeRatePerKw = 10000
  val minimumFee = Satoshi(1000)

  val master = DeterministicWallet.generate(BinaryData("01" * 32))
  val accountMaster = accountKey(master)
  val accountIndex = 0

  val changeMaster = changeKey(master)
  val changeIndex = 0

  val firstAccountKeys = (0 until 10).map(i => derivePrivateKey(accountMaster, i)).toVector
  val firstChangeKeys = (0 until 10).map(i => derivePrivateKey(changeMaster, i)).toVector
  val state = State(firstAccountKeys, firstChangeKeys, Set(), Map(), Map(), Map())

  test("compute addresses") {
    val priv = PrivateKey.fromBase58("cRumXueoZHjhGXrZWeFoEBkeDHu2m8dW5qtFBCqSAt4LDR2Hnd8Q", Base58.Prefix.SecretKeyTestnet)
    assert(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey.hash160) == "ms93boMGZZjvjciujPJgDAqeR86EKBf9MC")
    assert(segwitAddress(priv) == "2MscvqgGXMTYJNAY3owdUtgWJaxPUjH38Cx")
  }

  test("spend wallet transactions") {
    val priv = PrivateKey.fromBase58("cRumXueoZHjhGXrZWeFoEBkeDHu2m8dW5qtFBCqSAt4LDR2Hnd8Q", Base58.Prefix.SecretKeyTestnet)
    val tx = Transaction.read("020000000b27aa2b1dab7e3953c814b5cd7637fc1752c49fbc0cf90058210dde85963b2482000000004847304402207ba78760c8c32c2c0a21dad17b7c37ae1e789f87b11ebaaf59fd205a85ac09af02200c9c7cf675a3cc0b9bd36a13eada4784add2f9043c6691f2a0b3b9fec29ce54201feffffff57f6e3e48edc1626952365e75cb2052a894db87e0c3011ebf3729ac24b206e570000000049483045022100b049872b0881b5e6711b556b643e14fde6befd4893582d062e3175d9c385debf0220021c5f559ba11cebeb4267cdd734374a09c5ea50cfaf53bcae794fd2a289fd5401feffffff648e1cd01183fb692cd7e138315e63710e7454ce2a24502e58a8ccef494ec0ff0000000049483045022100dfa9963b9caeed1f047d0181b0afb27eb0580b192a9b604ec7a0fc12e074077a022057cea83aa672cbe3ad5c0b124904bce502022585b97f68d42c5f529050e0f3cc01feffffff82504b590f5e6a2fe18d32590cfb1bcf03c71f97ec0be4744aa1a22834f38840000000004847304402202b4578dd77428a8109c415bacf9424b25b23775fe0aa373e02c9c45b4d49dcb70220787b735ec0fef497f19ddfbb467e1845764a54edd169164c6dc3849bf0608f1a01feffffffa2fa83c3f5b1f412fe82153b7797b9ca15e6b17ed1c51271eab856915a0ed64500000000484730440220783dec27fb55604e8057f7d697c0053fefb3bd5bbd790a640892fb5efffd93fe02203a73c55fed58cae61aea0f84f05ec200638c549cb1169f1bff6669c46265ee4b01feffffffafff1a8f5b3725432783fab0103fee7807d7282456766abb5e221e1c473b2871000000004948304502210088ddc51a4920bc927b36aa1fa71c1e990f6348d27ecba91764abbcfb0df7cfd6022051793b49e60b7b4dc785da297c0b38470ec816786f9e3c36d828b3f00b0a891201feffffffb392c2f31e939e99792beb3b9ab42ba81ab4144127d068d95bb624bb5deaaec9000000004847304402206d6b8458d2dad7e72294d2c35b1cefb139ecfec4751b44ca4cc043c14b13610702203aa7bdb21e8b2c899cde5a32845a905be61bc0d3dc67acb1ed0a4b9f56f5957101feffffffbde0b291ad8739c380a89ba944d28579e843dd54067a76f87818bf1958ed7b7c000000004847304402202f891b50c39c83dd05adabb9a1518e36a342521871a9816f33557de79a0c674d02204f2492a2ef30d3f52e69fc4ff7295f2b6f5cf1d57cbb20ad379edf45b3c685c701feffffffcb113c9b074b946f5cfce18d8db4d1514a9970a1f9ee22f1edc7be495b5c13ee00000000494830450221008b99f276c0fdfaca1cad9fccdda90868c894f98629fc93953678bfc51f5517320220579138acc95c0c463316d713229dbe955221b76ed4d31a514bd77bbbe56e2b7a01fefffffffc99a50ee41fa5f2293f9703c31330b570953ad0f088dab9909daec9fd4eb1310000000049483045022100f9c9ff0f0b23adb398d64fde42c1e81024b7927c17ac2dd4d8ae4e19e1bdeabf0220409a8bb9ea57a8a185c501ebe91cc3887245397d563ed5d686b30059ff5861a901fefffffffeef1c0526ab5e9406b6be371da74e39e7cd788f2c6fe26ec6f42fccde20cf7e000000006a47304402204b92210a629e2cc27e9d02807e73e35e4c5da1d3bd72453819a4f1ea3c9714ec022012051986b4a17862e77d6f8405334fab2b1110ba7a794e68e395bdc50ef2d8e8012103dcc878e5f1ab54a07645380edddfbf366347ea13d8c0424d07917485bbc6f583feffffff0100e1f5050000000017a914041ad61f13080bd64b24ce15ae518d533b6e6dc28712100000")
    val ourScript = Script.write(Script.pay2sh(Script.pay2wpkh(priv.publicKey)))
    val pos = tx.txOut.indexWhere(_.publicKeyScript == ourScript)
    val tx1 = Transaction(version = 1,
      txIn = TxIn(OutPoint(tx, pos), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(tx.txOut(pos).amount, ourScript) :: Nil,
      lockTime = 0)
    val utxos = Set(Utxo(OutPoint(tx, pos),tx.txOut(pos).amount, priv, false))
    val sig = Transaction.signInput(tx1, 0, Script.pay2pkh(priv.publicKey), SIGHASH_ALL, tx.txOut(pos).amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
    val tx2 = tx1.updateWitness(0, ScriptWitness(sig :: priv.publicKey.toBin :: Nil)).updateSigScript(0, OP_PUSHDATA(Script.write(Script.pay2wpkh(priv.publicKey))) :: Nil)
    Transaction.correctlySpends(tx2, tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("complete transactions (enough funds)") {
    val utxos = Set(
      Utxo(OutPoint("01" * 32, 0), 1 btc, state.accountKeys(0).privateKey, false),
      Utxo(OutPoint("02" * 32, 0), 2 btc, state.accountKeys(1).privateKey, false),
      Utxo(OutPoint("03" * 32, 0), 3 btc, state.accountKeys(2).privateKey, false)
    )
    val state1 = state.copy(utxos = utxos, status = (state.accountKeys ++ state.changeKeys).map(key => scriptHash(key.publicKey) -> "").toMap)

    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1)  = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)

    val state3 = state2.cancelTransaction(tx1)
    assert(state3 == state1)

    val state4 = state2.commitTransaction(tx1)
    assert(state4.utxos.size + tx1.txIn.size == state1.utxos.size)
  }

  test("complete transactions (insufficient funds)") {
    val utxos = Set(
      Utxo(OutPoint("01" * 32, 0), 1 btc, state.accountKeys(0).privateKey, false),
      Utxo(OutPoint("02" * 32, 0), 2 btc, state.accountKeys(1).privateKey, false),
      Utxo(OutPoint("03" * 32, 0), 3 btc, state.accountKeys(2).privateKey, false)
    )
    val state1 = state.copy(utxos = utxos, status = (state.accountKeys ++ state.changeKeys).map(key => scriptHash(key.publicKey) -> "").toMap)

    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(6 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)

    val e = intercept[IllegalArgumentException] {
      val (state2, tx1)  = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)
    }
  }

  def extractPubkeySentTo(state: State, txOut: TxOut): Option[PublicKey] = {
    (state.accountKeys ++ state.changeKeys).map(_.publicKey).find(pub => txOut.publicKeyScript == publicKeyScript(pub))
  }

  test("find what a tx spends from us") {
    val utxos = Set(
      Utxo(OutPoint("01" * 32, 0), 1 btc, state.accountKeys(0).privateKey, false),
      Utxo(OutPoint("02" * 32, 0), 2 btc, state.accountKeys(1).privateKey, false),
      Utxo(OutPoint("03" * 32, 0), 3 btc, state.accountKeys(2).privateKey, false)
    )
    val state1 = state.copy(utxos = utxos, status = (state.accountKeys ++ state.changeKeys).map(key => scriptHash(key.publicKey) -> "").toMap)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1)  = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)


    val pubkeys = tx1.txIn.map(extractPubKeySpentFrom).flatten
    val utxos1 = state2.utxos.filter(utxo => pubkeys.contains(utxo.key.publicKey))
    val utxos2 = state2.utxos.filter(utxo => tx1.txIn.map(_.outPoint).contains(utxo.outPoint))

    println(pubkeys)
  }

  test("find what a tx sends to us") {
    val utxos = Set(
      Utxo(OutPoint("01" * 32, 0), 1 btc, state.accountKeys(0).privateKey, false),
      Utxo(OutPoint("02" * 32, 0), 2 btc, state.accountKeys(1).privateKey, false),
      Utxo(OutPoint("03" * 32, 0), 3 btc, state.accountKeys(2).privateKey, false)
    )
    val state1 = state.copy(utxos = utxos, status = (state.accountKeys ++ state.changeKeys).map(key => scriptHash(key.publicKey) -> "").toMap)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1)  = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit)


    val pubSpent = tx1.txIn.map(extractPubKeySpentFrom).flatten
    val utxos1 = state2.utxos.filter(utxo => pubSpent.contains(utxo.key.publicKey))
    val utxos2 = state2.utxos.filter(utxo => tx1.txIn.map(_.outPoint).contains(utxo.outPoint))


    println(pubSpent)
  }
}

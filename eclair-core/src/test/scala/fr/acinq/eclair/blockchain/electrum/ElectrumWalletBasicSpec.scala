package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import fr.acinq.bitcoin._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ElectrumWalletBasicSpec extends FunSuite {

  import ElectrumWallet._

  val swipeRange = 10
  val dustLimit = 546 satoshi
  val feeRatePerKw = 20000
  val minimumFee = Satoshi(2000)

  val master = DeterministicWallet.generate(BinaryData("01" * 32))
  val accountMaster = accountKey(master)
  val accountIndex = 0

  val changeMaster = changeKey(master)
  val changeIndex = 0

  val firstAccountKeys = (0 until 10).map(i => derivePrivateKey(accountMaster, i)).toVector
  val firstChangeKeys = (0 until 10).map(i => derivePrivateKey(changeMaster, i)).toVector

  val params = ElectrumWallet.WalletParameters(Block.RegtestGenesisBlock.hash)

  val state = Data(params, ElectrumClient.Header.RegtestGenesisHeader, firstAccountKeys, firstChangeKeys)
  val unspents = Map(
    computeScriptHashFromPublicKey(state.accountKeys(0).publicKey) -> Set(ElectrumClient.UnspentItem("01" * 32, 0, 1 * Satoshi(Coin).toLong, 100)),
    computeScriptHashFromPublicKey(state.accountKeys(1).publicKey) -> Set(ElectrumClient.UnspentItem("02" * 32, 0, 2 * Satoshi(Coin).toLong, 100)),
    computeScriptHashFromPublicKey(state.accountKeys(2).publicKey) -> Set(ElectrumClient.UnspentItem("03" * 32, 0, 3 * Satoshi(Coin).toLong, 100))
  )

  test("compute addresses") {
    val priv = PrivateKey.fromBase58("cRumXueoZHjhGXrZWeFoEBkeDHu2m8dW5qtFBCqSAt4LDR2Hnd8Q", Base58.Prefix.SecretKeyTestnet)
    assert(Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey.hash160) == "ms93boMGZZjvjciujPJgDAqeR86EKBf9MC")
    assert(segwitAddress(priv) == "2MscvqgGXMTYJNAY3owdUtgWJaxPUjH38Cx")
  }

  test("implement BIP49") {
    val mnemonics = "pizza afraid guess romance pair steel record jazz rubber prison angle hen heart engage kiss visual helmet twelve lady found between wave rapid twist".split(" ")
    val seed = MnemonicCode.toSeed(mnemonics, "")
    val master = DeterministicWallet.generate(seed)

    val accountMaster = accountKey(master)
    val firstKey = derivePrivateKey(accountMaster, 0)
    assert(segwitAddress(firstKey) === "2MxJejujQJRRJdbfTKNQQ94YCnxJwRaE7yo")
  }

  ignore("complete transactions (enough funds)") {
    val state1 = state.copy(status = (state.accountKeys ++ state.changeKeys).map(key => computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)

    val pub = PrivateKey(BinaryData("01" * 32), compressed = true).publicKey
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(pub)) :: Nil, lockTime = 0)
    val (state2, tx1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)

    val state3 = state2.cancelTransaction(tx1)
    assert(state3 == state1)

    val state4 = state2.commitTransaction(tx1)
    assert(state4.utxos.size + tx1.txIn.size == state1.utxos.size)
  }

  test("complete transactions (insufficient funds)") {
    val state1 = state.copy(status = (state.accountKeys ++ state.changeKeys).map(key => computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(6 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val e = intercept[IllegalArgumentException] {
      val (state2, tx1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)
    }
  }

  ignore("find what a tx spends from us") {
    val state1 = state.copy(status = (state.accountKeys ++ state.changeKeys).map(key => computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)

    val pubkeys = tx1.txIn.map(extractPubKeySpentFrom).flatten
    val utxos1 = state2.utxos.filter(utxo => pubkeys.contains(utxo.key.publicKey))
    val utxos2 = state2.utxos.filter(utxo => tx1.txIn.map(_.outPoint).contains(utxo.outPoint))
    println(pubkeys)
  }

  ignore("find what a tx sends to us") {
    val state1 = state.copy(status = (state.accountKeys ++ state.changeKeys).map(key => computeScriptHashFromPublicKey(key.publicKey) -> "").toMap)
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(0.5 btc, Script.pay2pkh(state1.accountKeys(0).publicKey)) :: Nil, lockTime = 0)
    val (state2, tx1) = state1.completeTransaction(tx, feeRatePerKw, minimumFee, dustLimit, false)

    val pubSpent = tx1.txIn.map(extractPubKeySpentFrom).flatten
    val utxos1 = state2.utxos.filter(utxo => pubSpent.contains(utxo.key.publicKey))
    val utxos2 = state2.utxos.filter(utxo => tx1.txIn.map(_.outPoint).contains(utxo.outPoint))
    println(pubSpent)
  }
}

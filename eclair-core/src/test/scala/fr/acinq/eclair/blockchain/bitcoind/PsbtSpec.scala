package fr.acinq.eclair.blockchain.bitcoind

import akka.testkit.TestProbe
import akka.pattern.pipe
import fr.acinq.bitcoin.{Block, ByteVector32, OutPoint, Psbt, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut, computeP2WpkhAddress}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{FundPsbtOptions, FundPsbtResponse, ProcessPsbtResponse}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass}
import grizzled.slf4j.Logging
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

class PsbtSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  implicit val formats: Formats = DefaultFormats
  lazy val bitcoinClient = new BitcoinCoreClient(Block.RegtestGenesisBlock.hash, bitcoinrpcclient)

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  def createOurTx(pub: PublicKey) : (Transaction, Int) = {
    val sender = TestProbe()
    bitcoinClient.fundPsbt(Seq(computeP2WpkhAddress(pub, Block.RegtestGenesisBlock.hash) -> 100000.sat), 0, FundPsbtOptions(TestConstants.feeratePerKw, lockUtxos = false)).pipeTo(sender.ref)
    val FundPsbtResponse(psbt, _, Some(changepos)) = sender.expectMsgType[FundPsbtResponse]
    bitcoinClient.processPsbt(psbt, sign = true).pipeTo(sender.ref)
    val ProcessPsbtResponse(psbt1, true) = sender.expectMsgType[ProcessPsbtResponse]
    val Success(tx) = psbt1.extract()
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    val publishedId = sender.expectMsgType[ByteVector32]
    assert(publishedId == tx.txid)
    val index = tx.txOut.zipWithIndex.find(_._1.publicKeyScript == Script.write(Script.pay2wpkh(pub))).get._2
    (tx, index)
  }

  test("add inputs") {
    val sender = TestProbe()

    // create a utxo that sends to a non-wallet key
    val priv = PrivateKey(ByteVector32.fromValidHex("01" * 32))
    val (ourTx, index) = createOurTx(priv.publicKey)

    // fund a psbt without inputs
    bitcoinClient.fundPsbt(Seq(computeP2WpkhAddress(priv.publicKey, Block.RegtestGenesisBlock.hash) -> 100000.sat), 0, FundPsbtOptions(TestConstants.feeratePerKw, lockUtxos = false)).pipeTo(sender.ref)
    val FundPsbtResponse(psbt, _, _) = sender.expectMsgType[FundPsbtResponse]

    val fakeTx = Transaction(version = 2, txIn = TxIn(OutPoint(ourTx, index), Nil, 0) :: Nil, txOut = Nil, lockTime = 0)
    val fakePsbt = Psbt(fakeTx)
    val joined = Psbt.join(psbt, fakePsbt).get
    val psbt1 = joined.updateWitnessInput(OutPoint(ourTx, index), ourTx.txOut(index), witnessScript = Some(Script.pay2pkh(priv.publicKey))).get
    val txWithAdditionalInput = psbt.global.tx.addInput(TxIn(OutPoint(ourTx, index), Nil, 0))

    // ask bitcoin core to sign its inputs
    bitcoinClient.processPsbt(psbt1).pipeTo(sender.ref)
    val ProcessPsbtResponse(psbt2, complete) = sender.expectMsgType[ProcessPsbtResponse] //(max = 10 minutes)

    // sign our inputs
    val Success(psbt3) = psbt2.sign(priv, 1)
    val sig = psbt3.sig

    // we now have a finalized PSBT
    val Success(psbt4) = psbt3.psbt.finalizeWitnessInput(1, ScriptWitness(sig :: priv.publicKey.value :: Nil))
    assert(psbt4.extract().isSuccess)
  }
}

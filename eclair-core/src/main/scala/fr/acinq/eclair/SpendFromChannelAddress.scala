package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector64, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut, addressToPublicKeyScript}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelConfig, ChannelSpendSignature}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import scodec.bits.ByteVector

import scala.concurrent.Future

trait SpendFromChannelAddress {

  this: EclairImpl =>

  private def buildTx(outPoint: OutPoint, outputAmount: Satoshi, pubKeyScript: ByteVector, witness: ScriptWitness) = Transaction(2,
    txIn = Seq(TxIn(outPoint, ByteVector.empty, 0, witness)),
    txOut = Seq(TxOut(outputAmount, pubKeyScript)),
    lockTime = 0)

  override def spendFromChannelAddressPrep(outPoint: OutPoint, fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long, address: String, feerate: FeeratePerKw): Future[SpendFromChannelPrep] = {
    for {
      inputTx <- appKit.wallet.getTransaction(outPoint.txid)
      inputAmount = inputTx.txOut(outPoint.index.toInt).amount
      Right(pubKeyScript) = addressToPublicKeyScript(appKit.nodeParams.chainHash, address).map(Script.write)
      channelKeys = appKit.nodeParams.channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
      localFundingPubkey = channelKeys.fundingKey(fundingTxIndex).publicKey
      // build the tx a first time with a zero amount to compute the weight
      dummyWitness = Scripts.witness2of2(PlaceHolderSig, PlaceHolderSig, localFundingPubkey, localFundingPubkey)
      fee = Transactions.weight2fee(feerate, buildTx(outPoint, 0.sat, pubKeyScript, dummyWitness).weight())
      _ = assert(inputAmount - fee > Scripts.dustLimit(pubKeyScript), s"amount insufficient (fee=$fee)")
      unsignedTx = buildTx(outPoint, inputAmount - fee, pubKeyScript, dummyWitness)
    } yield SpendFromChannelPrep(fundingTxIndex, localFundingPubkey, inputAmount, unsignedTx)
  }

  override def spendFromChannelAddress(fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long, remoteFundingPubkey: PublicKey, remoteSig: ByteVector64, unsignedTx: Transaction): Future[SpendFromChannelResult] = {
    for {
      _ <- Future.successful(())
      outPoint = unsignedTx.txIn.head.outPoint
      inputTx <- appKit.wallet.getTransaction(outPoint.txid)
      channelKeys = appKit.nodeParams.channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
      localFundingKey = channelKeys.fundingKey(fundingTxIndex)
      inputInfo = InputInfo(outPoint, inputTx.txOut(outPoint.index.toInt))
      // classify as splice, doesn't really matter
      tx = Transactions.SpliceTx(inputInfo, unsignedTx)
      localSig = tx.sign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty)
      signedTx = tx.aggregateSigs(localFundingKey.publicKey, remoteFundingPubkey, localSig, ChannelSpendSignature.IndividualSignature(remoteSig))
    } yield SpendFromChannelResult(signedTx)
  }

}

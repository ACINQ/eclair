package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector64, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut, addressToPublicKeyScript}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelConfig, Helpers}
import fr.acinq.eclair.transactions.Scripts.multiSig2of2
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import scodec.bits.ByteVector

import scala.concurrent.Future

trait SpendFromChannelAddress {

  this: EclairImpl =>

  /** these dummy witnesses are used as a placeholder to accurately compute the weight */
  private val dummy2of2Witness = Scripts.witness2of2(PlaceHolderSig, PlaceHolderSig, PlaceHolderPubKey, PlaceHolderPubKey)

  private def buildTx(outPoint: OutPoint, outputAmount: Satoshi, pubKeyScript: ByteVector, witness: ScriptWitness) = Transaction(2,
    txIn = Seq(TxIn(outPoint, ByteVector.empty, 0, witness)),
    txOut = Seq(TxOut(outputAmount, pubKeyScript)),
    lockTime = 0)

  override def spendFromChannelAddressPrep(outPoint: OutPoint, fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long, address: String, feerate: FeeratePerKw): Future[SpendFromChannelPrep] = {
    for {
      inputTx <- appKit.wallet.getTransaction(outPoint.txid)
      inputAmount = inputTx.txOut(outPoint.index.toInt).amount
      Right(pubKeyScript) = addressToPublicKeyScript(appKit.nodeParams.chainHash, address).map(Script.write)
      // build the tx a first time with a zero amount to compute the weight
      fee = Transactions.weight2fee(feerate, buildTx(outPoint, 0.sat, pubKeyScript, dummy2of2Witness).weight())
      _ = assert(inputAmount - fee > Scripts.dustLimit(pubKeyScript), s"amount insufficient (fee=$fee)")
      unsignedTx = buildTx(outPoint, inputAmount - fee, pubKeyScript, dummy2of2Witness)
      // the following are not used, but need to be sent to the counterparty
      channelKeys = appKit.nodeParams.channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
      localFundingPubkey = channelKeys.fundingKey(fundingTxIndex).publicKey
    } yield SpendFromChannelPrep(fundingTxIndex, localFundingPubkey, inputAmount, unsignedTx)
  }

  override def spendFromChannelAddress(fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long, remoteFundingPubkey: PublicKey, remoteSig: ByteVector64, unsignedTx: Transaction): Future[SpendFromChannelResult] = {
    for {
      _ <- Future.successful(())
      outPoint = unsignedTx.txIn.head.outPoint
      inputTx <- appKit.wallet.getTransaction(outPoint.txid)
      channelKeys = appKit.nodeParams.channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
      localFundingKey = channelKeys.fundingKey(fundingTxIndex)
      redeemInfo = Helpers.Funding.makeFundingRedeemInfo(localFundingKey.publicKey, remoteFundingPubkey, DefaultCommitmentFormat)
      inputInfo = InputInfo(outPoint, inputTx.txOut(outPoint.index.toInt))
      // classify as splice, doesn't really matter
      localSig = Transactions.SpliceTx(inputInfo, unsignedTx).sign(localFundingKey, redeemInfo, TxOwner.Local, DefaultCommitmentFormat, Map.empty)
      witness = Scripts.witness2of2(localSig, remoteSig, localFundingKey.publicKey, remoteFundingPubkey)
      signedTx = unsignedTx.updateWitness(0, witness)
    } yield SpendFromChannelResult(signedTx)
  }

}

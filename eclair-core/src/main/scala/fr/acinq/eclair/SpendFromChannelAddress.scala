package fr.acinq.eclair

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Musig2.{IndividualNonce, LocalNonce}
import fr.acinq.bitcoin.scalacompat.{DeterministicWallet, Musig2, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut, addressToPublicKeyScript}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelConfig, ChannelSpendSignature}
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.jdk.CollectionConverters.ConcurrentMapHasAsScala

trait SpendFromChannelAddress {

  this: EclairImpl =>

  import java.util.concurrent.ConcurrentHashMap
  private val nonces = new ConcurrentHashMap[IndividualNonce, LocalNonce]().asScala

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
      isTaproot = Script.isPay2tr(Script.parse(pubKeyScript))
      (localNonce_opt, dummyWitness) = if (isTaproot) {
        val serverNonce = Musig2.generateNonce(randomBytes32(), Right(localFundingPubkey), Seq(localFundingPubkey), None, None)
        nonces.put(serverNonce.publicNonce, serverNonce)
        Some(serverNonce.publicNonce) -> Script.witnessKeyPathPay2tr(PlaceHolderSig)
      } else {
        None -> Scripts.witness2of2(PlaceHolderSig, PlaceHolderSig, localFundingPubkey, localFundingPubkey)
      }
      // build the tx a first time with a zero amount to compute the weight
      fee = Transactions.weight2fee(feerate, buildTx(outPoint, 0.sat, pubKeyScript, dummyWitness).weight())
      _ = assert(inputAmount - fee > Scripts.dustLimit(pubKeyScript), s"amount insufficient (fee=$fee)")
      unsignedTx = buildTx(outPoint, inputAmount - fee, pubKeyScript, dummyWitness)
    } yield SpendFromChannelPrep(fundingTxIndex, localFundingPubkey, localNonce_opt, inputAmount, unsignedTx)
  }

  override def spendFromChannelAddress(fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long, remoteFundingPubkey: PublicKey, localNonce_opt: Option[IndividualNonce], remoteSig: ChannelSpendSignature, unsignedTx: Transaction): Future[SpendFromChannelResult] = {
    for {
      _ <- Future.successful(())
      outPoint = unsignedTx.txIn.head.outPoint
      inputTx <- appKit.wallet.getTransaction(outPoint.txid)
      inputInfo = InputInfo(outPoint, inputTx.txOut(outPoint.index.toInt))
      // classify as splice, doesn't really matter
      tx = Transactions.SpliceTx(inputInfo, unsignedTx)
      channelKeys = appKit.nodeParams.channelKeyManager.channelKeys(ChannelConfig.standard, fundingKeyPath)
      localFundingKey = channelKeys.fundingKey(fundingTxIndex)
      signedTx = remoteSig match {
        case individualRemoteSig: ChannelSpendSignature.IndividualSignature =>
          val localSig = tx.sign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty)
          tx.aggregateSigs(localFundingKey.publicKey, remoteFundingPubkey, localSig, individualRemoteSig)
        case remotePartialSig: ChannelSpendSignature.PartialSignatureWithNonce =>
          val localPrivateNonce = nonces(localNonce_opt.get)
          val Right(localSig) = tx.partialSign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty, localNonce = localPrivateNonce, publicNonces = Seq(localPrivateNonce.publicNonce, remotePartialSig.nonce))
          val Right(signedTx) = tx.aggregateSigs(localFundingKey.publicKey, remoteFundingPubkey, localSig, remotePartialSig, extraUtxos = Map.empty)
          signedTx
      }
    } yield SpendFromChannelResult(signedTx)
  }
}

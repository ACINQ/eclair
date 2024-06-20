package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Crypto, Script, computeBIP84Address}
import fr.acinq.eclair.blockchain.{AddressType, OnChainAddressGenerator}
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class OnchainPubkeyRefresherSpec extends TestKitBaseClass with AnyFunSuiteLike {
  test("renew onchain scripts") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    val finalPubkey = new AtomicReference[PublicKey](randomKey().publicKey)
    val finalPubkeyScript = new AtomicReference[ByteVector](Script.write(Script.pay2wpkh(randomKey().publicKey)))
    val generator = new OnChainAddressGenerator {
      override def getReceiveAddress(label: String, addressType_opt: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[String] = Future.successful(
        addressType_opt match {
          case Some(AddressType.Bech32m) => fr.acinq.bitcoin.Bitcoin.computeBIP86Address(randomKey().publicKey, Block.RegtestGenesisBlock.hash)
          case _ => computeBIP84Address(randomKey().publicKey, Block.RegtestGenesisBlock.hash)
        }
      )

      override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(randomKey().publicKey)
    }
    val manager = system.spawnAnonymous(OnchainPubkeyRefresher(Block.RegtestGenesisBlock.hash, generator, finalPubkey, finalPubkeyScript, 3 seconds))

    // renew pubkey explicitly
    val currentPubkey = finalPubkey.get()
    manager ! OnchainPubkeyRefresher.RenewPubkey
    awaitCond(finalPubkey.get() != currentPubkey)

    // renew pubkey script explicitly
    val currentPubkeyScript = finalPubkeyScript.get()
    manager ! OnchainPubkeyRefresher.RenewPubkeyScript
    awaitCond(finalPubkeyScript.get() != currentPubkeyScript)
  }
}

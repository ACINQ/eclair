package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Crypto, Script, ScriptElt, computeBIP84Address}
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class OnchainPubkeyRefresherSpec extends TestKitBaseClass with AnyFunSuiteLike {
  test("renew onchain scripts") {
    val finalPubkey = new AtomicReference[PublicKey](randomKey().publicKey)
    val finalPubkeyScript = new AtomicReference[Seq[ScriptElt]](Script.pay2tr(randomKey().xOnlyPublicKey()))
    val generator = new OnChainAddressGenerator {
      override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = Future.successful(addressType match {
        case Some(AddressType.P2tr) => Script.pay2tr(randomKey().xOnlyPublicKey())
        case _ => Script.pay2wpkh(randomKey().publicKey)
      })

      override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(randomKey().publicKey)
    }
    val manager = system.spawnAnonymous(OnchainPubkeyRefresher(generator, finalPubkey, finalPubkeyScript, 3 seconds))
    // renew pubkey explicitly
    val currentPubkey = finalPubkey.get()
    manager ! OnchainPubkeyRefresher.Renew
    awaitCond(finalPubkey.get() != currentPubkey)

    // renew pubkey explicitly
    val currentPubkeyScript = finalPubkeyScript.get()
    manager ! OnchainPubkeyRefresher.RenewPubkeyScript
    awaitCond(finalPubkeyScript.get() != currentPubkeyScript)
  }
}

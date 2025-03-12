package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Crypto, Script, ScriptElt}
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class OnChainAddressRefresherSpec extends TestKitBaseClass with AnyFunSuiteLike {

  test("renew on-chain addresses") {
    val finalPubkey = new AtomicReference[PublicKey](randomKey().publicKey)
    val finalPubkeyScript = new AtomicReference[Seq[ScriptElt]](Script.pay2tr(randomKey().xOnlyPublicKey()))
    val renewedPublicKeyCount = new AtomicInteger(0)
    val renewedPublicKeyScriptCount = new AtomicInteger(0)
    val generator = new OnChainAddressGenerator {
      override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = {
        renewedPublicKeyScriptCount.incrementAndGet()
        Future.successful(addressType match {
          case Some(AddressType.P2tr) => Script.pay2tr(randomKey().xOnlyPublicKey())
          case _ => Script.pay2wpkh(randomKey().publicKey)
        })
      }

      override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = {
        renewedPublicKeyCount.incrementAndGet()
        Future.successful(randomKey().publicKey)
      }
    }

    val manager = system.spawnAnonymous(OnChainAddressRefresher(generator, finalPubkey, finalPubkeyScript, 1 second))
    val initialPublicKey = finalPubkey.get()
    val initialScript = finalPubkeyScript.get()
    // We send a batch of requests to renew our public key and public key script.
    (1 to 7).foreach(_ => manager ! OnChainAddressRefresher.RenewPubkey)
    (1 to 5).foreach(_ => manager ! OnChainAddressRefresher.RenewPubkeyScript)
    // After a delay, we will have renewed the public key and script only once.
    awaitCond(finalPubkey.get() != initialPublicKey)
    awaitCond(finalPubkeyScript.get() != initialScript)
    assert(renewedPublicKeyCount.get() == 1)
    assert(renewedPublicKeyScriptCount.get() == 1)
  }

}

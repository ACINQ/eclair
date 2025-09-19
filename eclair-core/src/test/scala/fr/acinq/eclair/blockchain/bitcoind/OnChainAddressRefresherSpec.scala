package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import fr.acinq.bitcoin.scalacompat.{Script, ScriptElt}
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class OnChainAddressRefresherSpec extends TestKitBaseClass with AnyFunSuiteLike {

  test("renew on-chain addresses") {
    val finalPubkeyScript = new AtomicReference[Seq[ScriptElt]](Script.pay2tr(randomKey().xOnlyPublicKey()))
    val renewedCount = new AtomicInteger(0)
    val generator = new OnChainAddressGenerator {
      override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = {
        renewedCount.incrementAndGet()
        Future.successful(addressType match {
          case Some(AddressType.P2tr) => Script.pay2tr(randomKey().xOnlyPublicKey())
          case _ => Script.pay2wpkh(randomKey().publicKey)
        })
      }
    }

    val manager = system.spawnAnonymous(OnChainAddressRefresher(generator, finalPubkeyScript, 1 second))

    // We send a batch of requests to renew our public key script.
    val script1 = finalPubkeyScript.get()
    (1 to 5).foreach(_ => manager ! OnChainAddressRefresher.RenewPubkeyScript)
    awaitCond(finalPubkeyScript.get() != script1)
    assert(renewedCount.get() == 1)

    // We send another request to renew our public key script.
    val script2 = finalPubkeyScript.get()
    manager ! OnChainAddressRefresher.RenewPubkeyScript
    awaitCond(finalPubkeyScript.get() != script2)
    assert(renewedCount.get() == 2)
  }

}

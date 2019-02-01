package fr.acinq.eclair.tor

import java.io._
import java.net.InetSocketAddress
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.io.Tcp.Connected
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.ByteString
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.wire.NodeAddress
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, MustMatchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class TorProtocolHandlerSpec extends TestKit(ActorSystem("test"))
  with ImplicitSender
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterEach
  with BeforeAndAfterAll {

  import TorProtocolHandler._

  val LocalHost = new InetSocketAddress("localhost", 8888)
  val Password = "foobar"
  val PkFilePath = Paths.get(TestUtils.BUILD_DIRECTORY,"testtor.dat")

  override protected def beforeEach(): Unit = {
    super.afterEach()
    PkFilePath.toFile.delete()
  }

  "tor" ignore {

    val promiseOnionAddress = Promise[OnionAddress]()

    val protocolHandlerProps = TorProtocolHandler.props(
      password = Password,
      privateKeyPath = Paths.get(TestUtils.BUILD_DIRECTORY, "testtor.dat"),
      virtualPort = 9999,
      onionAdded = Some(promiseOnionAddress))

    val controller = TestActorRef(Controller.props(new InetSocketAddress("localhost", 9051), protocolHandlerProps), "tor")

    val address = Await.result(promiseOnionAddress.future, 30 seconds)
    println(address)
    println(address.onionService.length)
    println((address.onionService + ".onion").length)
    println(NodeAddress(address))
  }

  "happy path v3" in {

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        password = Password,
        privateKeyPath = PkFilePath,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress)), "happy-v3")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=HASHEDPASSWORD\r\n" +
          "250-VERSION Tor=\"0.3.4.8\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString(s"""AUTHENTICATE "$Password"\r\n"""))
      protocolHandler ! ByteString(
        "250 OK\r\n"
      )

      expectMsg(ByteString("ADD_ONION NEW:ED25519-V3 Port=9999,9999\r\n"))
      protocolHandler ! ByteString(
        "250-ServiceID=mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd\r\n" +
          "250-PrivateKey=ED25519-V3:private-key\r\n" +
          "250 OK\r\n"
      )

      protocolHandler ! GetOnionAddress
      expectMsg(Some(OnionAddressV3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 9999)))

      val address = Await.result(promiseOnionAddress.future, 3 seconds)
      address must be(OnionAddressV3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 9999))
      address.toOnion must be ("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd.onion:9999")
      NodeAddress(address).toString must be ("Tor3(6457a1ed0b38a73d56dc866accec93ca6af68bc316568874478dc9399cc1a0b3431b03,9999)")

      readString(PkFilePath) must be("ED25519-V3:private-key")
  }

  "v3 should handle AUTHENTICATE errors" in {

    val badPassword = "badpassword"

    val promiseOnionAddress = Promise[OnionAddress]()

    val protocolHandler = TestActorRef(props(
      password = badPassword,
      privateKeyPath = PkFilePath,
      virtualPort = 9999,
      onionAdded = Some(promiseOnionAddress)), "authchallenge-error")

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=HASHEDPASSWORD\r\n" +
        "250-VERSION Tor=\"0.3.4.8\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString(s"""AUTHENTICATE "$badPassword"\r\n"""))
    protocolHandler ! ByteString(
      "515 Authentication failed: Password did not match HashedControlPassword *or* authentication cookie.\r\n"
    )

    intercept[TorException] {
      Await.result(promiseOnionAddress.future, 3 seconds)
    }

  }

}
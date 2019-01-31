package fr.acinq.eclair.tor

import java.io.{File, IOException}
import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}
import java.util.Date

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.io.Tcp.Connected
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.util.ByteString
import fr.acinq.eclair.wire.NodeAddress
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

class TorProtocolHandlerSpec extends TestKit(ActorSystem("test"))
  with ImplicitSender
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll {

  import TorProtocolHandler._

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.terminate()
  }

  val LocalHost = new InetSocketAddress("localhost", 8888)
  val ClientNonce = "8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA"
  val AuthCookie = "AA8593C52DF9713CC5FF6A1D0A045B3FADCAE57745B1348A62A6F5F88D940485"

  "tor" ignore {

    val promiseOnionAddress = Promise[OnionAddress]()

    val protocolHandlerProps = TorProtocolHandler.props(
      version ="v2",
      privateKeyPath = sys.props("user.home") + "/v2_pk",
      virtualPort = 9999,
      onionAdded = Some(promiseOnionAddress),
      nonce = Some(unhex(ClientNonce)))

    val controller = TestActorRef(Controller.props(new InetSocketAddress("localhost", 9051), protocolHandlerProps), "tor")

    val address = Await.result(promiseOnionAddress.future, 30 seconds)
    println(address)
    println(address.onionService.length)
    println((address.onionService + ".onion").length)
    println(NodeAddress(address))
  }

  "happy path v2" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v2",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "happy-v2")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.3.5\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50DF SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(ByteString("AUTHENTICATE 0DDCAB5DEB39876CDEF7AF7860A1C738953395349F43B99F4E5E0F131B0515DF\r\n"))
      protocolHandler ! ByteString(
        "250 OK\r\n"
      )

      expectMsg(ByteString("ADD_ONION NEW:RSA1024 Port=9999,9999\r\n"))
      protocolHandler ! ByteString(
        "250-ServiceID=z4zif3fy7fe7bpg3\r\n" +
          "250-PrivateKey=RSA1024:private-key\r\n" +
          "250 OK\r\n"
      )

      protocolHandler ! GetOnionAddress
      expectMsg(Some(OnionAddressV2("z4zif3fy7fe7bpg3", 9999)))

      val address = Await.result(promiseOnionAddress.future, 3 seconds)
      address must be(OnionAddressV2("z4zif3fy7fe7bpg3", 9999))
      address.toOnion must be ("z4zif3fy7fe7bpg3.onion:9999")
      NodeAddress(address).toString must be ("Tor2(cf3282ecb8f949f0bcdb,9999)")

      readString(pkFile) must be("RSA1024:private-key")
    }
  }

  "happy path v3" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v3",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "happy-v3")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.4.8\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50DF SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(ByteString("AUTHENTICATE 0DDCAB5DEB39876CDEF7AF7860A1C738953395349F43B99F4E5E0F131B0515DF\r\n"))
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

      readString(pkFile) must be("ED25519-V3:private-key")
    }
  }

  "v3 should not be supported by 0.3.3.5" in {
    val protocolHandler = TestActorRef(props(
      version = "v3",
      privateKeyPath = "",
      virtualPort = 9999,
      onionAdded = None,
      nonce = Some(unhex(ClientNonce))), "unsupported-v3")

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"cookieFile\"\r\n" +
        "250-VERSION Tor=\"0.3.3.5\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(Failure(TorException("Tor version 0.3.3.5 does not support protocol V3")))
  }

  "v3 should handle AUTHCHALLENGE errors" in {

    val protocolHandler = TestActorRef(props(
      version = "v3",
      privateKeyPath = "",
      virtualPort = 9999,
      onionAdded = None,
      nonce = Some(unhex(ClientNonce))), "authchallenge-error")

    protocolHandler ! Connected(LocalHost, LocalHost)

    expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
    protocolHandler ! ByteString(
      "250-PROTOCOLINFO 1\r\n" +
        "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"cookieFile\"\r\n" +
        "250-VERSION Tor=\"0.3.4.8\"\r\n" +
        "250 OK\r\n"
    )

    expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
    protocolHandler ! ByteString(
      "513 Invalid base16 client nonce\r\n"
    )

    expectMsg(Failure(TorException("Tor server returned error: 513 Invalid base16 client nonce")))
  }

  "v2 should handle invalid cookie file" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie).take(2))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v2",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "invalid-cookie")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.3.5\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50DF SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(Failure(TorException("Invalid file length")))
    }
  }

  "v2 should handle server hash error" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v2",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "server-hash-error")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.3.5\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50D0 SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(Failure(TorException("Unexpected server hash")))
    }
  }

  "v2 should handle AUTHENTICATE failure" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v2",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "auth-error")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.3.5\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50DF SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(ByteString("AUTHENTICATE 0DDCAB5DEB39876CDEF7AF7860A1C738953395349F43B99F4E5E0F131B0515DF\r\n"))
      protocolHandler ! ByteString(
        "515 Authentication failed: Safe cookie response did not match expected value.\r\n"
      )
      expectMsg(Failure(TorException("Tor server returned error: 515 Authentication failed: Safe cookie response did not match expected value.")))
    }
  }

  "v2 should handle ADD_ONION failure" in {
    withTempDir { dir =>
      val cookieFile = dir + File.separator + "cookie"
      val pkFile = dir + File.separator + "pk"

      writeBytes(cookieFile, unhex(AuthCookie))

      val promiseOnionAddress = Promise[OnionAddress]()

      val protocolHandler = TestActorRef(props(
        version = "v2",
        privateKeyPath = pkFile,
        virtualPort = 9999,
        onionAdded = Some(promiseOnionAddress),
        nonce = Some(unhex(ClientNonce))), "add-onion-error")

      protocolHandler ! Connected(LocalHost, LocalHost)

      expectMsg(ByteString("PROTOCOLINFO 1\r\n"))
      protocolHandler ! ByteString(
        "250-PROTOCOLINFO 1\r\n" +
          "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"" + cookieFile + "\"\r\n" +
          "250-VERSION Tor=\"0.3.3.5\"\r\n" +
          "250 OK\r\n"
      )

      expectMsg(ByteString("AUTHCHALLENGE SAFECOOKIE 8969A7F3C03CD21BFD1CC49DBBD8F398345261B5B66319DF76BB2FDD8D96BCCA\r\n"))
      protocolHandler ! ByteString(
        "250 AUTHCHALLENGE SERVERHASH=6828E74049924F37CBC61F2AAD4DD78D8DC09BEF1B4C3BF6FF454016ED9D50DF SERVERNONCE=B4AA04B6E7E2DF60DCB0F62C264903346E05D1675E77795529E22CA90918DEE7\r\n"
      )

      expectMsg(ByteString("AUTHENTICATE 0DDCAB5DEB39876CDEF7AF7860A1C738953395349F43B99F4E5E0F131B0515DF\r\n"))
      protocolHandler ! ByteString(
        "250 OK\r\n"
      )

      expectMsg(ByteString("ADD_ONION NEW:RSA1024 Port=9999,9999\r\n"))
      protocolHandler ! ByteString(
        "513 Invalid argument\r\n"
      )

      expectMsg(Failure(TorException("Tor server returned error: 513 Invalid argument")))
    }
  }

  def withTempDir[T](f: String => T): T = {
    val d = Files.createTempDirectory("test-")
    try {
      f(d.toString)
    } finally {
      Files.walkFileTree(d, new SimpleFileVisitor[Path]() {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          Files.delete(dir)
          FileVisitResult.CONTINUE
        }
      })
    }
  }
}
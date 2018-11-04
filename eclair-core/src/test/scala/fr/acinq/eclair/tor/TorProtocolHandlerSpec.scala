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

  "tor" in {
    sys.props.put("socksProxyHost", "localhost")
    sys.props.put("socksProxyPort", "9050")

    val socksProxy = new InetSocketAddress(sys.props("socksProxyHost"), sys.props("socksProxyPort").toInt)
    val host = "hqa6yepjl2uwzn2y.onion"
    val port = 9735
//
//
//    class ClientHandler extends ChannelInboundHandlerAdapter {
//
//      val greetings = Unpooled.buffer(3)
//      greetings.writeByte(0x05)
//      greetings.writeByte(0x01)
//      greetings.writeByte(0x00)
//
//      val connReq = Unpooled.buffer(host.length + 6)
//      connReq.writeByte(0x05)
//      connReq.writeByte(0x01)
//      connReq.writeByte(0x00)
//      connReq.writeByte(0x03)
//      connReq.writeByte(host.length.toByte)
//      connReq.writeCharSequence(host, Charset.forName("UTF-8"))
//      connReq.writeByte(port.toByte)
//      connReq.writeByte((port >> 8).toByte)
//
//      println(Integer.toHexString(port))
//
//      sealed trait State
//      case object Init extends State
//      case object Error extends State
//      case object Authed extends State
//      case object Sent extends State
//
//      @volatile var state: State = Init
//
//
//      override def channelActive(ctx: ChannelHandlerContext): Unit = {
//        println("channelActive")
//        ctx.writeAndFlush(greetings)
//      }
//
//      override def channelInactive(ctx: ChannelHandlerContext): Unit = {
//        println("channelInactive")
//      }
//
//      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
//        println("exceptionCaught")
//        cause.printStackTrace()
//        ctx.close()
//      }
//
//      override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
//        println("channelReadComplete")
//        state match {
//          case Authed =>
//            ctx.writeAndFlush(connReq)
//            state = Sent
//          case x@_ =>
//            println(x)
//        }
//      }
//
//      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
//        println("channelRead")
//        val m = msg.asInstanceOf[ByteBuf] // (1)
//        println(m.readableBytes())
//        try {
//          state match {
//            case Init =>
//              if (m.readByte() != 0x05) {
//                throw new RuntimeException()
//              }
//              if (m.readByte() != 0x00) {
//                throw new RuntimeException()
//              }
//              state = Authed
//            case Sent =>
//              if (m.readByte() != 0x05) {
//                throw new RuntimeException()
//              }
//              val x = m.readByte()
//              println(x)
//              if (x != 0x00) {
//                //throw new RuntimeException()
//              }
//              val y = m.readByte()
//              println(y)
//              val z = m.readByte()
//              println(z)
//              if (z != 0x01) {
//                //throw new RuntimeException()
//              }
//              val ip = Array(m.readByte(), m.readByte(), m.readByte(), m.readByte())
//              val addr = InetAddress.getByAddress(ip)
//
//              val p = m.readByte().toInt << 8 | m.readByte()
//              println(addr)
//              println(p)
//
//              val buf = Unpooled.buffer
//              buf.writeCharSequence(host, Charset.forName("UTF-8"))
//              ctx.writeAndFlush(buf)
//
//              //ctx.close()
//            case x@_ =>
//              println(x)
//          }
//        } catch {
//          case e: Throwable =>
//            e.printStackTrace()
//            state = Error
//        } finally {
//          m.release
//        }
//      }
//    }
//

//    val workerGroup: EventLoopGroup = new NioEventLoopGroup
//    try {
//      val b = new Bootstrap() // (1)
//      b.group(workerGroup) // (2)
//      b.channel(classOf[NioSocketChannel]) // (3)
//      b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true) // (4)
//      b.handler(new ChannelInitializer[SocketChannel] {
//        override def initChannel(ch: SocketChannel): Unit = {
////          ch.pipeline.addFirst(new Socks5ProxyHandler(socksProxy, "uuuuuuuu", "pppppppp"))
//          ch.pipeline.addLast(new ClientHandler())
//        }
//      })
//      // Start the client.
//      val f = b.connect(socksProxy).sync() // (5)
//
//      // Wait until the connection is closed.
//      f.channel().closeFuture().sync()
//    } finally workerGroup.shutdownGracefully()

    /*
    val workerGroup: EventLoopGroup = new NioEventLoopGroup
    try {
      val b = new Bootstrap() // (1)
      b.group(workerGroup) // (2)
      b.channel(classOf[NioSocketChannel]) // (3)
      b.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true) // (4)
      b.handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel): Unit = {
          ch.pipeline.addLast(new ClientHandler())
        }
      })
      // Start the client.
      val f = b.connect(socksProxy).sync() // (5)

      // Wait until the connection is closed.
      f.channel().closeFuture().sync()
    } finally workerGroup.shutdownGracefully()
*/
    //    val channel = SocketChannel.open(socksProxy)
//    val b = ByteBuffer.allocate(3)
//    b.put(0x05.toByte)
//    b.put(0x01.toByte)
//    b.put(0x00.toByte)
//    channel.write(b)
//    val b1 = ByteBuffer.allocate(2)
//    channel.read(b1)
//    println(b1)


//    val promiseInetAddress = Some(Promise[InetSocketAddress]())

//    val facilitator = TestActorRef(SocksConnectionFacilitator.props(promiseInetAddress), "socks")
//    val controller = TestActorRef(Controller.props(socksProxy, facilitator, Some(new InetSocketAddress(host, port))), "controller")

//    expectMsg(125 seconds, SocksConnected(null))
//    val inet = Await.result(promiseInetAddress.future, 125 seconds)
//    println(inet)

//    val workerGroup = new NioEventLoopGroup()
//    try {
//      val b = new Bootstrap()
//      b.group(workerGroup)
//      b.channel(classOf[NioSocketChannel])
//      b.option(ChannelOption.SO_KEEPALIVE, true)

//      b.handler(new ChannelInitializer[SocketChannel] {
//        override def initChannel(ch: SocketChannel): Unit = {
//          println("initChannel")
////          ch.pipeline().addFirst(new Socks5ProxyHandler(new InetSocketAddress(sys.props("socksProxyHost"), sys.props("socksProxyPort").toInt)))
////          ch.pipeline.addLast(new Nothing)
//        }
//      })

//      val isa = new InetSocketAddress("hqa6yepjl2uwzn2y.onion", 9735)
//      val ia = isa.getAddress
//      println(ia)

//      val f = b.connect("hqa6yepjl2uwzn2y.onion", 9735).sync
////      val f = b.connect("bitcoin.org", 80).sync
//      f.channel().closeFuture().sync()
//    } finally
//      workerGroup.shutdownGracefully()



    //    val socketChannel = java.nio.channels.SocketChannel.open()
//    socketChannel.connect(new InetSocketAddress("hqa6yepjl2uwzn2y.onion", 9735))

//    val u = new java.net.URL("http://hqa6yepjl2uwzn2y.onion:9735/")
//    val c = u.openConnection()
//    val r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream))
//    try {
//      r.lines().forEach(new java.util.function.Consumer[String] {
//        override def accept(t: String): Unit = {
//          println(t)
//        }
//      })
//    } finally {
//      r.close()
//    }

/*
    val promiseOnionAddress = Promise[OnionAddress]()

    val protocolHandler = TestActorRef(TorProtocolHandler.props(
      version ="v2",
      privateKeyPath = sys.props("user.home") + "/v2_pk",
      virtualPort = 9999,
      onionAdded = Some(promiseOnionAddress),
      nonce = None), //Some(unhex(ClientNonce))),
      "tor-proto")

    val controller = TestActorRef(Controller.props(new InetSocketAddress("localhost", 9051), protocolHandler), "tor")

    val address = Await.result(promiseOnionAddress.future, 30 seconds)
    println(address)
    println(address.onionService.length)
    println((address.onionService + ".onion").length)
    println(NodeAddress(address))
    */
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
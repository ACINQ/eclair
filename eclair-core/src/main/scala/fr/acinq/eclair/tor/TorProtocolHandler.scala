package fr.acinq.eclair.tor

import java.io._
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{FileSystems, Files, Paths}

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.io.Tcp.Connected
import akka.util.ByteString
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.Promise

case class TorException(msg: String) extends RuntimeException(msg)

/**
  * Created by rorp
  *
  * Specification: https://gitweb.torproject.org/torspec.git/tree/control-spec.txt
  *
  * @param password       Tor controller password
  * @param privateKeyPath path to a file that contains a Tor private key
  * @param virtualPort    port of our protected local server (typically 9735)
  * @param targetPorts    target ports of the public hidden service
  * @param onionAdded     a Promise to track creation of the endpoint
  */
class TorProtocolHandler(password: String,
                         privateKeyPath: String,
                         virtualPort: Int,
                         targetPorts: Seq[Int],
                         onionAdded: Option[Promise[OnionAddress]]
                        ) extends Actor with Stash with ActorLogging {

  import TorProtocolHandler._

  private var receiver: ActorRef = _

  private var address: Option[OnionAddress] = None

  override def receive: Receive = {
    case Connected(_, _) =>
      receiver = sender()
      sendCommand("PROTOCOLINFO 1")
      context become protocolInfo
  }

  def protocolInfo: Receive = {
    case data: ByteString =>
      val res = parseResponse(readResponse(data))
      val version = unquote(res.getOrElse("Tor", throw TorException("Tor version not found")))
      log.info(s"Tor version $version ")
      sendCommand(s"""AUTHENTICATE "$password"""")
      context become authenticate
  }

  def authenticate: Receive = {
    case data: ByteString =>
      readResponse(data)
      sendCommand(s"ADD_ONION $computeKey $computePort")
      context become addOnion
  }

  def addOnion: Receive = {
    case data: ByteString =>
      val res = readResponse(data)
      if (ok(res)) {
        val serviceId = processOnionResponse(parseResponse(res))
        address = Some(OnionAddressV3(serviceId, virtualPort))
        onionAdded.foreach(_.success(address.get))
        log.debug(s"Onion address: ${address.get}")
      }
  }


  override def aroundReceive(receive: Receive, msg: Any): Unit = try {
    super.aroundReceive(receive, msg)
  } catch {
    case t: Throwable => onionAdded.map(_.tryFailure(t))
  }

  override def unhandled(message: Any): Unit = message match {
    case GetOnionAddress =>
      sender() ! address
  }

  private def processOnionResponse(res: Map[String, String]): String = {
    val serviceId = res.getOrElse("ServiceID", throw TorException("Tor service ID not found"))
    val privateKey = res.get("PrivateKey")
    privateKey.foreach { pk =>
      writeString(privateKeyPath, pk)
      setPermissions(privateKeyPath, "rw-------")
    }
    serviceId
  }

  private def computeKey: String = {
    if (Files.exists(Paths.get(privateKeyPath))) {
      readString(privateKeyPath)
    } else {
      "NEW:ED25519-V3"
    }
  }

  private def computePort: String = {
    if (targetPorts.isEmpty) {
      s"Port=$virtualPort,$virtualPort"
    } else {
      targetPorts.map(p => s"Port=$virtualPort,$p").mkString(" ")
    }
  }

  private def sendCommand(cmd: String): Unit = {
    receiver ! ByteString(s"$cmd\r\n")
  }
}

object TorProtocolHandler {
  def props(password: String,
            privateKeyPath: String,
            virtualPort: Int,
            targetPorts: Seq[Int] = Seq(),
            onionAdded: Option[Promise[OnionAddress]] = None
           ): Props =
    Props(new TorProtocolHandler(password, privateKeyPath, virtualPort, targetPorts, onionAdded))

  // those are defined in the spec
  private val ServerKey: Array[Byte] = "Tor safe cookie authentication server-to-controller hash".getBytes()
  private val ClientKey: Array[Byte] = "Tor safe cookie authentication controller-to-server hash".getBytes()

  case object GetOnionAddress

  def readString(filename: String): String = {
    val r = new BufferedReader(new FileReader(filename))
    try {
      r.readLine()
    } finally {
      r.close()
    }
  }

  def writeString(filename: String, string: String): Unit = {
    val w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(filename)))
    try {
      w.print(string)
    } finally {
      w.close()
    }
  }

  def setPermissions(filename: String, permissionString: String): Unit = {
    val path = FileSystems.getDefault.getPath(filename)
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissionString))
    } catch {
      case _: UnsupportedOperationException => () // we are on windows
    }
  }

  def unquote(s: String): String = s
    .stripSuffix("\"")
    .stripPrefix("\"")
    .replace("""\\""", """\""")
    .replace("""\"""", "\"")

  private val r1 = """(\d+)\-(.*)""".r
  private val r2 = """(\d+) (.*)""".r

  def readResponse(bstr: ByteString): Seq[(Int, String)] = {
    val lines = bstr.utf8String.split('\n')
      .map(_.stripSuffix("\r"))
      .filterNot(_.isEmpty)
      .map {
        case r1(c, msg) => (c.toInt, msg)
        case r2(c, msg) => (c.toInt, msg)
        case x@_ => throw TorException(s"Unknown response line format: `$x`")
      }
    if (!ok(lines)) {
      throw TorException(s"Tor server returned error: ${status(lines)} ${reason(lines)}")
    }
    lines
  }

  def ok(res: Seq[(Int, String)]): Boolean = status(res) == 250

  def status(res: Seq[(Int, String)]): Int = res.lastOption.map(_._1).getOrElse(-1)

  def reason(res: Seq[(Int, String)]): String = res.lastOption.map(_._2).getOrElse("Unknown error")

  private val r = """([^=]+)=(.+)""".r

  def parseResponse(lines: Seq[(Int, String)]): Map[String, String] = {
    lines.flatMap {
      case (_, message) =>
        message.split(" ")
          .collect {
            case r(k, v) => (k, v)
          }
    }.toMap
  }

  def hmacSHA256(key: Array[Byte], message: Array[Byte]): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = new SecretKeySpec(key, "HmacSHA256")
    mac.init(secretKey)
    mac.doFinal(message)
  }
}
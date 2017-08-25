package fr.acinq.eclair

import java.io.File

import fr.acinq.eclair.blockchain.spv.BitcoinjKit2
import fr.acinq.eclair.blockchain.wallet.BitcoinjWallet
import grizzled.slf4j.Logging

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  case class CmdLineConfig(datadir: File = new File(System.getProperty("user.home"), ".eclair"), textui: Boolean = false)

  val parser = new scopt.OptionParser[CmdLineConfig]("eclair") {
    head("eclair", s"${getClass.getPackage.getImplementationVersion} (commit: ${getClass.getPackage.getSpecificationVersion})")
    help("help").abbr("h").text("display usage text")
    opt[File]("datadir").optional().valueName("<file>").action((x, c) => c.copy(datadir = x)).text("optional data directory, default is ~/.eclair")
    opt[Unit]("textui").optional().action((_, c) => c.copy(textui = true)).text("runs eclair with a text-based ui")
  }

  try {
    parser.parse(args, CmdLineConfig()) match {
      case Some(config) =>
        LogSetup.logTo(config.datadir)
        val kit = new BitcoinjKit2("test", config.datadir)
        kit.startAsync()
        import scala.concurrent.ExecutionContext.Implicits.global
        val wallet = new BitcoinjWallet(kit.initialized.map(_ => kit.wallet()))
        val setup = new Setup(config.datadir, wallet_opt = Some(wallet))
        setup.bootstrap.collect {
          case kit if (config.textui) => new Textui(kit)
        } onFailure {
          case t: Throwable => onError(t)
        }
      case None => System.exit(0)
    }
  } catch {
    case t: Throwable => onError(t)
  }

  def onError(t: Throwable): Unit = {
    System.err.println(s"fatal error: ${t.getMessage}")
    logger.error(s"fatal error: ${t.getMessage}")
    System.exit(1)
  }
}


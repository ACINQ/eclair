package fr.acinq.eclair.tor

import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.softwaremill.sttp.{SttpBackend, SttpBackendOptions}
import fr.acinq.eclair.randomBytes

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object SttpUtils {

  private lazy val backends = new SttpBackendHolder

  def backend(socksProxy_opt: Option[Socks5ProxyParams]): SttpBackend[Future, Nothing] = backends.get(socksProxy_opt)

  private class SttpBackendHolder {
    @volatile
    @transient
    private var backends = Map.empty[Option[Socks5ProxyParams], SttpBackend[Future, Nothing]]

    def get(socksProxy_opt: Option[Socks5ProxyParams]): SttpBackend[Future, Nothing] = synchronized {
      backends.get(socksProxy_opt) match {
        case Some(backend) => backend
        case None =>
          val backend = createSttpBackend(socksProxy_opt)
          backends = backends.updated(socksProxy_opt, backend)
          backend
      }
    }

    private def createSttpBackend(socksProxy_opt: Option[Socks5ProxyParams]): SttpBackend[Future, Nothing] = {
      val options = SttpBackendOptions(connectionTimeout = 30.seconds, proxy = None)
      val sttpBackendOptions = socksProxy_opt match {
        case Some(proxy) =>
          val host = proxy.address.getHostString
          val port = proxy.address.getPort
          if (proxy.randomizeCredentials)
            options.socksProxy(host, port, username = randomBytes(16).toHex, password = randomBytes(16).toHex)
          else
            options.socksProxy(host, port)
        case None => options
      }
      OkHttpFutureBackend(sttpBackendOptions)
    }


  }

}

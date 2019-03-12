package fr.acinq.eclair.api

import akka.http.scaladsl.server.Route

trait MetaService {

  val route: Route

}

package fr.acinq.eclair.api

import akka.http.scaladsl.server.Route

// TODO remove this as soon as we remove Service.scala
trait MetaService {

  val route: Route

}

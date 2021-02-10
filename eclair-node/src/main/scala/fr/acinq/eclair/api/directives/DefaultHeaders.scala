package fr.acinq.eclair.api.directives

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives.respondWithDefaultHeaders

trait DefaultHeaders {

  /**
   * Adds customHeaders to all http responses.
   */
  def eclairHeaders:Directive0 = respondWithDefaultHeaders(customHeaders)


  private val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
      `Access-Control-Allow-Methods`(POST) ::
      `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil
}

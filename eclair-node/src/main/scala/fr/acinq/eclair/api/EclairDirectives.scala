package fr.acinq.eclair.api

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.{Directive, Directives}
import akka.util.Timeout
import fr.acinq.eclair.api.directives._

import scala.concurrent.duration.DurationInt

class EclairDirectives extends Directives with TimeoutDirective with ErrorDirective
  with AuthDirective with ExtraDirectives { this: Service =>

  def eclairRoute: Directive[Tuple1[Timeout]] = respondWithDefaultHeaders(customHeaders) &
    handled & toStrictEntity(paramParsingTimeout) & authenticated & withTimeout

  def postRequest(p:String):Directive[Tuple1[Timeout]] = post & path(p) & eclairRoute

  def getRequest(p:String):Directive[Tuple1[Timeout]] = get & path(p) & eclairRoute


  private val paramParsingTimeout = 5 seconds

  private val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

}

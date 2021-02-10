package fr.acinq.eclair.api.directives

import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.directives.Credentials
import fr.acinq.eclair.api.{EclairDirectives, Service}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait AuthDirective {
  this: Service with EclairDirectives =>

  /**
   * A directive0 that passes whenever valid basic credentials are provided. We
   * are not interested in the extracted username.
   *
   * @return
   */
  def authenticated: Directive0 = authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator).tflatMap { _ => pass }


  private def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = actorSystem.scheduler)(Future.successful(None))(actorSystem.dispatcher) // force a 1 sec pause to deter brute force
  }

}

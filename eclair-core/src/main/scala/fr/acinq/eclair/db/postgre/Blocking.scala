package fr.acinq.eclair.db.postgre

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile.backend.Database
import scala.concurrent.Await

object Blocking {
  val span: Duration = 30.seconds
  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)
}
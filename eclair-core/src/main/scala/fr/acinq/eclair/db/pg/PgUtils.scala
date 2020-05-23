/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db.pg

import java.sql.{Connection, Statement, Timestamp}
import java.util.UUID

import fr.acinq.eclair.db.jdbc.JdbcUtils
import grizzled.slf4j.Logging
import javax.sql.DataSource
import org.postgresql.util.{PGInterval, PSQLException}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object PgUtils extends JdbcUtils with Logging {

  val LeaseTable = "lease"

  val lockTimeout = FiniteDuration(5, "s")

  object LockType extends Enumeration {
    type LockType = Value

    val NONE, LEASE = Value

    def apply(s: String): LockType = s match {
      case "none" => NONE
      case "lease" => LEASE
      case _ => throw new RuntimeException(s"Unknown postgres lock type: `$s`")
    }
  }

  case class LockLease(expiresAt: Timestamp, instanceId: UUID, expired: Boolean)

  class TooManyLockAttempts(msg: String) extends RuntimeException(msg)

  class UninitializedLockTable(msg: String) extends RuntimeException(msg)

  class LockException(msg: String, cause: Option[Throwable] = None) extends RuntimeException(msg, cause.orNull)

  class LeaseException(msg: String) extends RuntimeException(msg)

  type LockExceptionHandler = LockException => Unit

  sealed trait DatabaseLock {
    def obtainExclusiveLock(implicit ds: DataSource): Unit

    def withLock[T](f: Connection => T)(implicit ds: DataSource): T
  }

  case object NoLock extends DatabaseLock {
    override def obtainExclusiveLock(implicit ds: DataSource): Unit = ()

    override def withLock[T](f: Connection => T)(implicit ds: DataSource): T =
      inTransaction(f)
  }

  case class LeaseLock(instanceId: UUID, leaseDuration: FiniteDuration, lockExceptionHandler: LockExceptionHandler) extends DatabaseLock {
    override def obtainExclusiveLock(implicit ds: DataSource): Unit =
      obtainDatabaseLease(instanceId, leaseDuration)

    override def withLock[T](f: Connection => T)(implicit ds: DataSource): T = {
      inTransaction { connection =>
        val res = f(connection)
        checkDatabaseLease(connection, instanceId, lockExceptionHandler)
        res
      }
    }
  }

  def inTransaction[T](connection: Connection)(f: Connection => T): T = {
    val autoCommit = connection.getAutoCommit
    connection.setAutoCommit(false)
    try {
      val res = f(connection)
      connection.commit()
      res
    } catch {
      case ex: Throwable =>
        connection.rollback()
        throw ex
    } finally {
      connection.setAutoCommit(autoCommit)
    }
  }

  def inTransaction[T](f: Connection => T)(implicit dataSource: DataSource): T = {
    withConnection { connection =>
      inTransaction(connection)(f)
    }
  }

  /**
    * Several logical databases (channels, network, peers) may be stored in the same physical postgres database.
    * We keep track of their respective version using a dedicated table. The version entry will be created if
    * there is none but will never be updated here (use setVersion to do that).
    */
  def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // if there was no version for the current db, then insert the current version
    statement.executeUpdate(s"INSERT INTO versions VALUES ('$db_name', $currentVersion) ON CONFLICT DO NOTHING")
    // if there was a previous version installed, this will return a different value from current version
    val res = statement.executeQuery(s"SELECT version FROM versions WHERE db_name='$db_name'")
    res.next()
    res.getInt("version")
  }

  /**
    * Updates the version for a particular logical database, it will overwrite the previous version.
    */
  def setVersion(statement: Statement, db_name: String, newVersion: Int): Unit = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // overwrite the existing version
    statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
  }

  private def obtainDatabaseLease(instanceId: UUID, leaseDuration: FiniteDuration, attempt: Int = 1)(implicit ds: DataSource): Unit = synchronized {
    logger.debug(s"Trying to acquire database lease (attempt #$attempt) instance ID=${instanceId}")

    if (attempt > 3) throw new TooManyLockAttempts("Too many attempts to acquire database lease")

    try {
      inTransaction { implicit connection =>
        acquireExclusiveTableLock()
        getCurrentLease match {
          case Some(lease) =>
            if (lease.instanceId == instanceId || lease.expired)
              updateLease(instanceId, leaseDuration)
            else
              throw new LeaseException(s"The database is locked by instance ID=${lease.instanceId}")
          case None =>
            updateLease(instanceId, leaseDuration, insertNew = true)
        }
      }
      logger.debug("Database lease was successfully acquired.")
    } catch {
      case e: PSQLException if (e.getServerErrorMessage != null && e.getServerErrorMessage.getSQLState == "42P01") =>
        withConnection {
          connection =>
            logger.warn(s"Table $LeaseTable does not exist, trying to recreate it...")
            initializeLeaseTable(connection)
            obtainDatabaseLease(instanceId, leaseDuration, attempt + 1)
        }
    }
  }

  private def initializeLeaseTable(implicit connection: Connection): Unit = {
    using(connection.createStatement()) {
      statement =>
        // allow only one row in the ownership lease table
        statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS $LeaseTable (id INTEGER PRIMARY KEY default(1), expires_at TIMESTAMP NOT NULL, instance VARCHAR NOT NULL, CONSTRAINT one_row CHECK (id = 1))")
    }
  }

  private def acquireExclusiveTableLock()(implicit connection: Connection): Unit = {
    using(connection.createStatement()) {
      statement =>
        statement.executeUpdate(s"SET lock_timeout TO '${lockTimeout.toSeconds}s'")
        statement.executeUpdate(s"LOCK TABLE $LeaseTable IN ACCESS EXCLUSIVE MODE")
    }
  }

  private def checkDatabaseLease(connection: Connection, instanceId: UUID, lockExceptionHandler: LockExceptionHandler): Unit = {
    Try {
      getCurrentLease(connection) match {
        case Some(lease) =>
          if (!(lease.instanceId == instanceId) || lease.expired) {
            logger.info(s"Database lease: $lease")
            throw new LockException("This Eclair instance is not a database owner")
          }
        case None =>
          throw new LockException("No database lease info")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        val lex = ex match {
          case e: LockException => e
          case t: Throwable => new LockException("Cannot check database lease", Some(t))
        }
        lockExceptionHandler(lex)
        throw lex
    }
  }

  private def getCurrentLease(implicit connection: Connection): Option[LockLease] = {
    using(connection.createStatement()) {
      statement =>
        val rs = statement.executeQuery(s"SELECT expires_at, instance, now() > expires_at AS expired FROM $LeaseTable WHERE id = 1")
        if (rs.next())
          Some(LockLease(
            expiresAt = rs.getTimestamp("expires_at"),
            instanceId = UUID.fromString(rs.getString("instance")),
            expired = rs.getBoolean("expired")))
        else
          None
    }
  }

  private def updateLease(instanceId: UUID, leaseDuration: FiniteDuration, insertNew: Boolean = false)(implicit connection: Connection): Unit = {
    val sql = if (insertNew)
      s"INSERT INTO $LeaseTable (expires_at, instance) VALUES (now() + ?, ?)"
    else
      s"UPDATE $LeaseTable SET expires_at = now() + ?, instance = ? WHERE id = 1"
    using(connection.prepareStatement(sql)) {
      statement =>
        statement.setObject(1, new PGInterval(s"${
          leaseDuration.toSeconds
        } seconds"))
        statement.setString(2, instanceId.toString)
        statement.executeUpdate()
    }
  }

}

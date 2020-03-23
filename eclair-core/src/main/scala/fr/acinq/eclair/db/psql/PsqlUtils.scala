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

package fr.acinq.eclair.db.psql

import java.sql.{Connection, Statement, Timestamp}
import java.util.concurrent.atomic.AtomicLong

import fr.acinq.eclair.db.jdbc.JdbcUtils
import grizzled.slf4j.Logging
import javax.sql.DataSource
import org.postgresql.util.{PGInterval, PSQLException}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

object PsqlUtils extends JdbcUtils with Logging {

  val DataVersionTable = "data_version"

  val OwnershipTable = "lease"

  object LockType extends Enumeration {
    type LockType = Value

    val NONE, OWNERSHIP_LEASE, OPTIMISTIC = Value

    def apply(s: String): LockType = s match {
      case "none" => NONE
      case "ownership-lease" => OWNERSHIP_LEASE
      case "optimistic" => OPTIMISTIC
      case _ => throw new RuntimeException(s"Unknown psql lock type: `$s`")
    }
  }

  case class LockLease(expiresAt: Timestamp, instanceId: String, expired: Boolean)

  class TooManyLockAttempts(msg: String) extends RuntimeException(msg)

  class UninitializedLockTable(msg: String) extends RuntimeException(msg)

  class LockException(msg: String, cause: Option[Throwable] = None) extends RuntimeException(msg, cause.orNull)

  class OwnershipException(msg: String) extends RuntimeException(msg)

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

  case class OwnershipLeaseLock(instanceId: String, leaseDuration: FiniteDuration, lockTimeout: FiniteDuration, lockExceptionHandler: LockExceptionHandler) extends DatabaseLock {
    override def obtainExclusiveLock(implicit ds: DataSource): Unit =
      obtainDatabaseOwnership(instanceId, leaseDuration, lockTimeout)

    override def withLock[T](f: Connection => T)(implicit ds: DataSource): T = {
      inTransaction { connection =>
        val res = f(connection)
        checkDatabaseOwnership(connection, instanceId, lockTimeout, lockExceptionHandler)
        res
      }
    }
  }

  case class OptimisticLock(dataVersion: AtomicLong, lockExceptionHandler: LockExceptionHandler) extends DatabaseLock {
    override def obtainExclusiveLock(implicit ds: DataSource): Unit =
      synchronized(inTransaction(c => dataVersion.set(getDataVersion(c))))

    override def withLock[T](f: Connection => T)(implicit ds: DataSource): T = {
      synchronized { inTransaction { connection =>
        val res = f(connection)
        incrementDataVersion(connection, dataVersion, lockExceptionHandler)
        res
      }}
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
    * Several logical databases (channels, network, peers) may be stored in the same physical sqlite database.
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

  private def incrementDataVersion(connection: Connection, dataVersion: AtomicLong, lockExceptionHandler: LockExceptionHandler): Long = {
    using(connection.prepareStatement(s"UPDATE $DataVersionTable SET data_version = data_version + 1 WHERE data_version = ?")) {
      statement =>
        statement.setLong(1, dataVersion.get())
        if (statement.executeUpdate() != 1) {
          val ex = new LockException(s"Unexpected data version `$dataVersion.get`")
          lockExceptionHandler(ex)
          throw ex
        }
        dataVersion.incrementAndGet()
    }
  }

  private def getDataVersion(implicit connection: Connection): Long = {
    initializeDataVersionTable(connection)
    using(connection.prepareStatement(s"SELECT data_version FROM $DataVersionTable WHERE id = 1")) {
      statement =>
        val rs = statement.executeQuery()
        if (rs.next())
          rs.getLong("data_version")
        else
          throw new LockException("Cannot read data version")
    }
  }

  private def initializeDataVersionTable(implicit connection: Connection): Unit = {
    using(connection.createStatement()) {
      statement =>
        // allow only one row in the data version lease table
        statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS $DataVersionTable (id INTEGER PRIMARY KEY default(1), data_version BIGINT UNIQUE, CONSTRAINT one_row CHECK (id = 1))")
        statement.executeUpdate(s"INSERT INTO ${DataVersionTable} (data_version) VALUES (0) ON CONFLICT DO NOTHING")
    }
  }

  private def obtainDatabaseOwnership(instanceId: String, leaseDuration: FiniteDuration, lockTimeout: FiniteDuration, attempt: Int = 1)(implicit ds: DataSource): Unit = synchronized {
    logger.debug(s"Trying to acquire database ownership (attempt #$attempt) instance ID=${instanceId}")

    if (attempt > 3) throw new TooManyLockAttempts("Too many attempts to acquire database ownership")

    try {
      inTransaction { implicit connection =>
        acquireExclusiveTableLock(lockTimeout)
        getCurrentLease match {
          case Some(lease) =>
            if (lease.instanceId == instanceId || lease.expired)
              updateLease(instanceId, leaseDuration)
            else
              throw new OwnershipException(s"The database is locked by instance ID=${lease.instanceId}")
          case None =>
            updateLease(instanceId, leaseDuration, insertNew = true)
        }
      }
      logger.debug("Database ownership was successfully acquired.")
    } catch {
      case e: PSQLException if (e.getServerErrorMessage != null && e.getServerErrorMessage.getSQLState == "42P01") =>
        withConnection {
          connection =>
            logger.warn(s"Table $OwnershipTable does not exist, trying to recreate it...")
            initializeOwnershipTable(connection)
            obtainDatabaseOwnership(instanceId, leaseDuration, lockTimeout, attempt + 1)
        }
    }
  }

  private def initializeOwnershipTable(implicit connection: Connection): Unit = {
    using(connection.createStatement()) {
      statement =>
        // allow only one row in the ownership lease table
        statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS $OwnershipTable (id INTEGER PRIMARY KEY default(1), expires_at TIMESTAMP NOT NULL, instance VARCHAR NOT NULL, CONSTRAINT one_row CHECK (id = 1))")
    }
  }

  private def acquireExclusiveTableLock(lockTimeout: FiniteDuration)(implicit connection: Connection): Unit = {
    using(connection.createStatement()) {
      statement =>
        statement.executeUpdate(s"SET lock_timeout TO '${lockTimeout.toSeconds}s'")
        statement.executeUpdate(s"LOCK TABLE $OwnershipTable IN ACCESS EXCLUSIVE MODE")
    }
  }

  private def checkDatabaseOwnership(connection: Connection, instanceId: String, lockTimeout: FiniteDuration, lockExceptionHandler: LockExceptionHandler): Unit = {
    Try {
      getCurrentLease(connection) match {
        case Some(lease) =>
          if (!(lease.instanceId == instanceId) || lease.expired) {
            logger.info(s"Database lease: $lease")
            throw new LockException("This Eclair instance is not a database owner")
          }
        case None =>
          throw new LockException("No database ownership info")
      }
    } match {
      case Success(_) => ()
      case Failure(ex) =>
        val lex = ex match {
          case e: LockException => e
          case t: Throwable => new LockException("Cannot check database ownership", Some(t))
        }
        lockExceptionHandler(lex)
        throw lex
    }
  }

  private def getCurrentLease(implicit connection: Connection): Option[LockLease] = {
    using(connection.createStatement()) {
      statement =>
        val rs = statement.executeQuery(s"SELECT expires_at, instance, now() > expires_at AS expired FROM $OwnershipTable WHERE id = 1")
        if (rs.next())
          Some(LockLease(
            expiresAt = rs.getTimestamp("expires_at"),
            instanceId = rs.getString("instance"),
            expired = rs.getBoolean("expired")))
        else
          None
    }
  }

  private def updateLease(instanceId: String, leaseDuration: FiniteDuration, insertNew: Boolean = false)(implicit connection: Connection): Unit = {
    val sql = if (insertNew)
      s"INSERT INTO $OwnershipTable (expires_at, instance) VALUES (now() + ?, ?)"
    else
      s"UPDATE $OwnershipTable SET expires_at = now() + ?, instance = ? WHERE id = 1"
    using(connection.prepareStatement(sql)) {
      statement =>
        statement.setObject(1, new PGInterval(s"${
          leaseDuration.toSeconds
        } seconds"))
        statement.setString(2, instanceId)
        statement.executeUpdate()
    }
  }

}

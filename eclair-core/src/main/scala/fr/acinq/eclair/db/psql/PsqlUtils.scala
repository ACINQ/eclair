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

import fr.acinq.eclair.db.jdbc.JdbcUtils
import grizzled.slf4j.Logging
import javax.sql.DataSource
import org.postgresql.util.{PGInterval, PSQLException}

import scala.concurrent.duration.FiniteDuration

object PsqlUtils extends JdbcUtils with Logging {

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

  val LockTable = "lock_leases"

  case class LockLease(expiresAt: Timestamp, instance: String, expired: Boolean)

  class TooManyLockAttempts(msg: String) extends RuntimeException(msg)

  class UninitializedLockTable(msg: String) extends RuntimeException(msg)

  class DatabaseLocked(msg: String) extends RuntimeException(msg)

  def obtainExclusiveLock(instanceId: String, leaseDuration: FiniteDuration, lockTimeout: FiniteDuration, attempt: Int = 1)(implicit ds: DataSource): Unit = synchronized {
    logger.info(s"Trying to acquire a database lock (attempt #$attempt) instance ID=$instanceId")

    if (attempt > 3) throw new TooManyLockAttempts("Too many attempts to acquire a database lock")

    try {
      withConnection { implicit connection =>
        val autocommit = connection.getAutoCommit
        try {
          connection.setAutoCommit(false)
          acquireExclusiveTableLock(lockTimeout)
          getCurrentLease match {
            case Some(lease) =>
              if (lease.instance == instanceId || lease.expired)
                updateLease(instanceId, leaseDuration)
              else
                throw new DatabaseLocked(s"The database is locked by instance ID=${lease.instance}")
            case None =>
              updateLease(instanceId, leaseDuration, insertNew = true)
          }
          connection.commit()
          logger.info("Database lock was successfully acquired.")
        } catch {
          case e: Throwable =>
            connection.rollback()
            throw e
        } finally {
          connection.setAutoCommit(autocommit)
        }
      }
    } catch {
      case e: PSQLException if (e.getServerErrorMessage != null && e.getServerErrorMessage.getSQLState == "42P01") =>
        withConnection { connection =>
          logger.warn(s"Table ${LockTable} does not exist, trying to recreate it...")
          initializeLocksTable(connection)
          obtainExclusiveLock(instanceId, leaseDuration, lockTimeout, attempt + 1)
        }
    }
  }

  private def initializeLocksTable(implicit connection: Connection): Unit = {
    using(connection.createStatement()) { statement =>
      // allow only one row in the lease table
      statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS $LockTable (id INTEGER PRIMARY KEY default(1), expires_at TIMESTAMP NOT NULL, instance VARCHAR NOT NULL, CONSTRAINT one_row CHECK (id = 1))")
    }
  }

  private def acquireExclusiveTableLock(lockTimeout: FiniteDuration)(implicit connection: Connection): Unit = {
    using(connection.createStatement()) { statement =>
      statement.executeUpdate(s"SET lock_timeout TO '${lockTimeout.toSeconds}s'")
      statement.executeUpdate(s"LOCK TABLE $LockTable IN ACCESS EXCLUSIVE MODE NOWAIT")
    }
  }

  private def getCurrentLease(implicit connection: Connection): Option[LockLease] = {
    using(connection.createStatement()) { statement =>
      val rs = statement.executeQuery(s"SELECT expires_at, instance, now() > expires_at AS expired FROM $LockTable WHERE id = 1")
      if (rs.next())
        Some(LockLease(
          expiresAt = rs.getTimestamp("expires_at"),
          instance = rs.getString("instance"),
          expired = rs.getBoolean("expired")))
      else
        None
    }
  }

  private def updateLease(instanceId: String, leaseDuration: FiniteDuration, insertNew: Boolean = false)(implicit connection: Connection): Unit = {
    val sql = if (insertNew)
      s"INSERT INTO $LockTable (expires_at, instance) VALUES (now() + ?, ?)"
    else
      s"UPDATE $LockTable SET expires_at = now() + ?, instance = ? WHERE id = 1"
    using(connection.prepareStatement(sql)) { statement =>
      statement.setObject(1, new PGInterval(s"${leaseDuration.toSeconds} seconds"))
      statement.setString(2, instanceId)
      statement.executeUpdate()
    }
  }

}

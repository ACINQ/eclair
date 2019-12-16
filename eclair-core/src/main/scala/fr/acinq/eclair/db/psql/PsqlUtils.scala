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

  case class LockLease(time: Timestamp, instance: String, expired: Boolean)

  class TooManyLockAttempts(msg: String) extends RuntimeException(msg)

  class UninitializedLockTable(msg: String) extends RuntimeException(msg)

  class DatabaseLocked(msg: String) extends RuntimeException(msg)

  def obtainExclusiveLock(instanceId: String, leaseDuration: FiniteDuration, attempt: Int = 1)(implicit ds: DataSource): Unit = synchronized {
    logger.info(s"Trying to obtain a database lock (attempt #$attempt)")

    if (attempt > 3) throw new TooManyLockAttempts("Too many attempts to obtain a database lock")

    try {
      withConnection { implicit connection =>
        val autocommit = connection.getAutoCommit
        try {
          connection.setAutoCommit(false)
          acquireExclusiveTableLock
          val lease = currentLease
          if (lease.instance == instanceId) {
            updateLease(instanceId, leaseDuration)
          } else if (lease.expired)
            updateLease(instanceId, leaseDuration, startNew = true)
          else
            throw new DatabaseLocked(s"The database is locked by ${lease.instance}")
          connection.commit()
          logger.info("Database lock was successfully aquired.")
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
          logger.warn(s"${LockTable} does not exist, trying to recreate it...")
          initializeLocksTable(connection)
          obtainExclusiveLock(instanceId, leaseDuration, attempt + 1)
        }
    }
  }

  private def initializeLocksTable(implicit connection: Connection): Unit = {
    using(connection.createStatement()) { statement =>
      statement.executeUpdate(s"CREATE TABLE $LockTable (time TIMESTAMP, instance VARCHAR)")
      // insert a dummy lock
      statement.executeUpdate(s"INSERT INTO $LockTable (time) VALUES (now())")
      Thread.sleep(100) // make sure that the dummy lock has expired
    }
  }

  private def acquireExclusiveTableLock(implicit connection: Connection): Unit = {
    using(connection.createStatement()) { statement =>
      statement.executeUpdate(s"LOCK TABLE $LockTable IN ACCESS EXCLUSIVE MODE")
    }
  }

  private def currentLease(implicit connection: Connection): LockLease = {
    using(connection.createStatement()) { statement =>
      val rs = statement.executeQuery(s"SELECT time, instance, CASE WHEN time IS NULL THEN true ELSE now() > time END AS expired FROM $LockTable ORDER BY time DESC LIMIT 1")
      if (rs.next())
        LockLease(
          time = rs.getTimestamp("time"),
          instance = rs.getString("instance"),
          expired = rs.getBoolean("expired")
        )
      else
        throw new UninitializedLockTable("The lock table seems to be empty. Do not know what to do.")
    }
  }

  private def updateLease(instanceId: String, leaseDuration: FiniteDuration, startNew: Boolean = false)(implicit connection: Connection): Unit = {
    val sql = if (startNew)
      s"INSERT INTO $LockTable (time, instance) VALUES (now() + ?, ?)"
    else
      s"UPDATE $LockTable SET time = now() + ? WHERE instance = ?"
    using(connection.prepareStatement(sql)) { statement =>
      statement.setObject(1, new PGInterval(s"${leaseDuration.toSeconds} seconds"))
      statement.setString(2, instanceId)
      statement.executeUpdate()
    }
  }

}

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

package fr.acinq.eclair.db

import fr.acinq.eclair.db.Databases.JdbcUrlChanged
import fr.acinq.eclair.db.jdbc.JdbcUtils
import fr.acinq.eclair.db.sqlite.SqliteUtils.using
import fr.acinq.eclair.{TestDatabases, TestUtils}
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.nio.file.Files
import java.sql.SQLException
import java.util.UUID

class SqliteUtilsSpec extends AnyFunSuite {

  test("using with auto-commit disabled") {
    val conn = TestDatabases.sqliteInMemory()

    using(conn.createStatement()) { statement =>
      statement.executeUpdate("CREATE TABLE utils_test (id INTEGER NOT NULL PRIMARY KEY, updated_at INTEGER)")
      statement.executeUpdate("INSERT INTO utils_test VALUES (1, 1)")
      statement.executeUpdate("INSERT INTO utils_test VALUES (2, 2)")
    }

    using(conn.createStatement()) { statement =>
      val results = statement.executeQuery("SELECT * FROM utils_test ORDER BY id")
      assert(results.next())
      assert(results.getLong("id") == 1)
      assert(results.next())
      assert(results.getLong("id") == 2)
      assert(!results.next())
    }

    assertThrows[SQLException](JdbcUtils.using(conn.createStatement(), inTransaction = true) { statement =>
      statement.executeUpdate("INSERT INTO utils_test VALUES (3, 3)")
      statement.executeUpdate("INSERT INTO utils_test VALUES (1, 3)") // should throw (primary key violation)
    })

    using(conn.createStatement()) { statement =>
      val results = statement.executeQuery("SELECT * FROM utils_test ORDER BY id")
      assert(results.next())
      assert(results.getLong("id") == 1)
      assert(results.next())
      assert(results.getLong("id") == 2)
      assert(!results.next())
    }

    using(conn.createStatement(), inTransaction = true) { statement =>
      statement.executeUpdate("INSERT INTO utils_test VALUES (3, 3)")
      statement.executeUpdate("INSERT INTO utils_test VALUES (4, 4)")
    }

    using(conn.createStatement()) { statement =>
      val results = statement.executeQuery("SELECT * FROM utils_test ORDER BY id")
      assert(results.next())
      assert(results.getLong("id") == 1)
      assert(results.next())
      assert(results.getLong("id") == 2)
      assert(results.next())
      assert(results.getLong("id") == 3)
      assert(results.next())
      assert(results.getLong("id") == 4)
      assert(!results.next())
    }
  }

  test("jdbc url check") {
    val datadir = new File(TestUtils.BUILD_DIRECTORY, s"sqlite_test_${UUID.randomUUID()}")
    datadir.mkdirs()
    val jdbcUrlPath = new File(datadir, "last_jdbcurl")

    // first start : write to file
    val db1 = Databases.sqlite(datadir, Some(jdbcUrlPath))
    db1.channels.sqlite.close()

    assert(Files.readString(jdbcUrlPath.toPath).trim == "sqlite")

    // 2nd start : no-op
    val db2 = Databases.sqlite(datadir, Some(jdbcUrlPath))
    db2.channels.sqlite.close()

    // we modify the file
    Files.writeString(jdbcUrlPath.toPath, "postgres")

    // boom
    intercept[JdbcUrlChanged] {
      Databases.sqlite(datadir, Some(jdbcUrlPath))
    }
  }

}

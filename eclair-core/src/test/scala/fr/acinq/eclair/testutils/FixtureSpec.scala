/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.testutils

import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.FixtureAnyFunSuite
import org.scalatest.{Assertions, Outcome, TestData}

/**
 * This is an opinionated base class for tests that need a fixture. It is a very thin layer on top of Scalatest, which
 * follows the recommended way of using fixtures, and hides boiler plate code.
 *
 * Usage:
 * {{{
 *   class MyTestSpec extends FixtureSpec {
 *
 *      type FixtureParam = MyFixtureClass
 *
 *      override def createFixture(testData: TestData): MyFixtureClass = {
 *        // will be run for each test
 *        // build the fixture here
 *        // customize with testData.tags if appropriate
 *        ...
 *      }
 *
 *      override def cleanupFixture(fixture: MyFixtureClass): Unit = {
 *        fixture.cleanup() // it is a good practice to define a cleanup method in your fixture case class
 *      }
 *
 *      test("my first test") { f =>
 *         import f._
 *         // write your test here
 *         ...
 *      }
 *   }
 * }}}
 *
 * Recommendations:
 *  - on test parallelization:
 *   - do not use [[org.scalatest.ParallelTestExecution]], parallelize on test suites instead
 *   - keep the execution time of each suite under one minute
 *  - on actor testing:
 *   - use a normal [[akka.actor.ActorSystem]] and real actors, use [[akka.testkit.TestProbe]] to communicate with them
 *   - do not use [[akka.testkit.TestKit]] class
 *   - do not use [[akka.testkit.TestActorRef]]
 *
 * References:
 *  - https://hseeberger.github.io/2017/09/13/2017-09-13-how-to-use-akka-testkit/
 *  - https://markuseliasson.se/article/scalatest-best-practices/
 */
abstract class FixtureSpec extends FixtureAnyFunSuite
  with Assertions
  with Eventually {

  def createFixture(testData: TestData): FixtureParam

  def cleanupFixture(fixture: FixtureParam): Unit

  final override def withFixture(test: OneArgTest): Outcome = {
    var doLog = true
    try {
      val fixture = createFixture(test)
      val outcome = try {
        withFixture(test.toNoArgTest(fixture))
      } finally {
        cleanupFixture(fixture)
      }
      doLog = !outcome.isSucceeded
      outcome
    } finally {
      maybeLog(test.name, doLog)
    }
  }

  final def maybeLog(testName: String, doLog: Boolean): Unit = {
    val loggerFactory = MyContextSelector.Singleton.getLoggerContext(testName)
    val root = loggerFactory.getLogger("ROOT")
    val capturingAppender = root.getAppender("MyCapturingAppender").asInstanceOf[MyCapturingAppender]
    if (doLog) {
      println(s"START OF LOGS FOR TEST '$testName'")
      capturingAppender.flush()
      println(s"END OF LOGS FOR TEST '$testName'")
    } else {
      capturingAppender.clear()
    }
  }

}
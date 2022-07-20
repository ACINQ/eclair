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

import akka.actor.{ActorSystem, Props}
import akka.event.Logging.{LogEvent, StdOutLogger}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import org.scalatest.concurrent.Eventually
import org.scalatest.funsuite.FixtureAnyFunSuite
import org.scalatest.{Assertions, Outcome, TestData}

import scala.collection.mutable.ListBuffer

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

  val logCapture = new MyLogCapture()

  def createFixture(testData: TestData): FixtureParam

  def cleanupFixture(fixture: FixtureParam): Unit

  override def withFixture(test: OneArgTest): Outcome = {
    val fixture = createFixture(test)
    logCapture.setup(fixture)
    val outcome = try {
      withFixture(test.toNoArgTest(fixture))
    } finally {
      cleanupFixture(fixture)
    }
    logCapture.maybePrintLogs(test.name, outcome)
    outcome
  }

}

class MyLogCapture extends StdOutLogger {

  // constant-time append
  private val logEvents: ListBuffer[LogEvent] = ListBuffer[LogEvent]()

  def setup(f: Any): Unit = {
    // we use reflection to find actorsystems to listen to
    val testSystem = f.getClass.getDeclaredMethods
      .collect {
        case m if m.getReturnType == classOf[ActorSystem] => m.invoke(f).asInstanceOf[ActorSystem]
      }.head
    val systems = f.getClass.getDeclaredMethods
      .collect {
        case m if m.getReturnType == classOf[MinimalNodeFixture] => m.invoke(f).asInstanceOf[MinimalNodeFixture].system
      }
      .toSet
    val l = testSystem.actorOf(Props(new LogListener(logEvents)))
    systems.foreach(l ! LogListener.ListenTo(_))
  }

  def maybePrintLogs(testName: String, outcome: Outcome): Unit = {
    if (!outcome.isSucceeded) {
      println(s"START OF LOGS FOR TEST $testName")
      logEvents.foreach(print)
      println(s"END OF LOGS FOR TEST $testName")
    }
  }

}

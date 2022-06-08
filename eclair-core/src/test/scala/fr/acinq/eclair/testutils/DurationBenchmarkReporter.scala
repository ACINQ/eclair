package fr.acinq.eclair.testutils

import org.scalatest.Reporter
import org.scalatest.events.{Event, RunCompleted, SuiteCompleted, TestSucceeded}

/**
 * A scalatest reporter that displays the slowest tests and suites. Useful to detect pain points and speed up the
 * overall build.
 *
 * To activate, edit the project's pom.xml and set:
 * {{{
 *   <artifactId>scalatest-maven-plugin</artifactId>
 *    ...
 *    <configuration>
 *      <parallel>false</parallel>
 *      <reporters>fr.acinq.eclair.testutils.DurationBenchmarkReporter</reporters>
 *      ...
 *    </configuration>
 * }}}
 *
 * Then run the tests as usual.
 *
 * Note that test parallelization needs to be disabled to have accurate measurement of durations, which will considerably
 * slow down the build time.
 */
class DurationBenchmarkReporter() extends Reporter {

  val suites = collection.mutable.Map.empty[String, Long]
  val tests = collection.mutable.Map.empty[String, Long]

  override def apply(event: Event): Unit = event match {
    case e: TestSucceeded =>
      e.duration.map(d => tests.addOne(e.testName, d))
    case e: SuiteCompleted =>
      e.duration.map(d => suites.addOne(e.suiteName, d))
    case _: RunCompleted =>
      println("top 20 slowest tests:")
      tests.toList.sortBy(-_._2).take(20).foreach { case (name, duration) => println(s"  ${duration}ms\t${name} ") }
      println("top 20 slowest suites:")
      suites.toList.sortBy(-_._2).take(20).foreach { case (name, duration) => println(s"  ${duration}ms\t${name} ") }
    case _ =>
      ()
  }
}

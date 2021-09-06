package fr.acinq.eclair.router

import fr.acinq.eclair.router.Router.RouteParams

import scala.util.Random

case class PathFindingExperimentConf(experiments: Map[String, RouteParams]) {
  private val confByPercentile: Array[RouteParams] = experiments.values.flatMap(e => Array.fill(e.experimentPercentage)(e)).toArray

  require(confByPercentile.length == 100, "All experiments percentages must sum to 100.")

  private val rng = new Random()

  def getRandomConf(): RouteParams = {
    confByPercentile(rng.nextInt(100))
  }

  def getByName(name: String): Option[RouteParams] = {
    experiments.get(name)
  }
}

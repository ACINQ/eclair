package fr.acinq.eclair.router

import fr.acinq.eclair.router.Router.PathFindingConf

import scala.util.Random

case class PathFindingExperimentConf(experiments: Map[String, PathFindingConf]) {
  private val confByPercentile: Array[PathFindingConf] = experiments.values.flatMap(e => Array.fill(e.experimentPercentage)(e)).toArray

  require(confByPercentile.length == 100, "All experiments percentages must sum to 100.")

  private val rng = new Random()

  def getRandomConf(): PathFindingConf = {
    confByPercentile(rng.nextInt(100))
  }

  def getByName(name: String): Option[PathFindingConf] = {
    experiments.get(name)
  }
}

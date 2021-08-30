package fr.acinq.eclair.router

import fr.acinq.eclair.router.Router.PathFindingConf

import scala.util.Random

case class PathFindingExperimentConf(experiments: List[PathFindingConf]) {
  private val confByPercentile: Array[PathFindingConf] = new Array[PathFindingConf](100)

  {
    var i = 0
    for (conf <- experiments) {
      for (j <- 0 until conf.experimentPercentage) {
        confByPercentile(i + j) = conf
      }
      i += conf.experimentPercentage
    }
    require(i == 100, "All experiments percentages must sum to 100.")
  }

  private val rng = new Random()

  def get(): PathFindingConf = {
    confByPercentile(rng.nextInt(100))
  }
}

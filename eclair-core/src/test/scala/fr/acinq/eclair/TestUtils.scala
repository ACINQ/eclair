package fr.acinq.eclair

import java.io.File

object TestUtils {

  /**
    * Get the module's target directory (works from command line and within intellij)
    */
  val BUILD_DIRECTORY = sys
    .props
    .get("buildDirectory") // this is defined if we run from maven
    .getOrElse(new File(sys.props("user.dir"), "target").getAbsolutePath) // otherwise we probably are in intellij, so we build it manually assuming that user.dir == path to the module

}

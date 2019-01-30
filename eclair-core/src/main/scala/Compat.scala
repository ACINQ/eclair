package akka

/**
  * Used for compatibility (akka.Done isn't in akka 2.3)
  */
sealed trait Done

case object Done extends Done

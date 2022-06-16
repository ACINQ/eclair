package fr.acinq.eclair

/**
 * Since actors are initialized asynchronously, and the initialization sometimes involves subscribing to an
 * [[akka.event.EventStream]], we don't know when they are ready to process messages, especially in tests, which
 * leads to race conditions.
 * By making actors publish [[IAmReady]] on the same [[akka.event.EventStream]] they are subscribing to, we guarantee
 * that if we receive [[IAmReady]] the actor has been initialized and its subscriptions have been taken into account.
 */
case class IAmReady(clazz: Class[_])

package fr.acinq.eclair

import java.util.concurrent.atomic.AtomicLong


/**
  * Created by PM on 25/01/2016.
  */
object Globals {

  /**
    * This counter holds the current blockchain height.
    * It is mainly used to calculate htlc expiries.
    * The value is updated by the [[fr.acinq.eclair.blockchain.PeerWatcher]] and read by all actors, hence it needs to be thread-safe.
    */
  val blockCount = new AtomicLong(0)

  /**
    * This counter holds the current feeratePerKw.
    * It is used to maintain an up-to-date fee in commitment tx so that they get confirmed fast enough.
    * The value is updated by the [[fr.acinq.eclair.blockchain.PeerWatcher]] and read by all actors, hence it needs to be thread-safe.
    */
  val feeratePerKw = new AtomicLong(0)
}



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

  object Constants {

    /**
      * Funder will send an UpdateFee message if the difference between current commitment fee and actual current network fee is greater
      * than this ratio.
      */
    val UPDATE_FEE_MIN_DIFF_RATIO = 0.1

    /**
      * Fundee will unilaterally close the channel if the difference between the feeratePerKw proposed by the funder in an UpdateFee and
      * actual current network fee is greater than this ratio.
      */
    val UPDATE_FEE_MAX_DIFF_RATIO = 0.3
  }

}



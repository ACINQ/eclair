package fr.acinq.eclair

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import fr.acinq.eclair.blockchain.fee.{FeeratesPerByte, FeeratesPerKw}


/**
  * Created by PM on 25/01/2016.
  */
object Globals {

  /**
    * This counter holds the current blockchain height.
    * It is mainly used to calculate htlc expiries.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val blockCount = new AtomicLong(0)

  /**
    * This holds the current feerates, in satoshi-per-bytes.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val feeratesPerByte = new AtomicReference[FeeratesPerByte](null)

  /**
    * This holds the current feerates, in satoshi-per-kw.
    * The value is read by all actors, hence it needs to be thread-safe.
    */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)
}



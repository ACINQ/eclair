package fr.acinq.eclair.blockchain.zmq

/**
  * Created by PM on 04/04/2017.
  */
sealed trait ZMQEvents

case object ZMQConnected extends ZMQEvents

case object ZMQDisconnected extends ZMQEvents

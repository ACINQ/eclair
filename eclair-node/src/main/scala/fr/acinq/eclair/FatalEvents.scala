package fr.acinq.eclair

/**
  * Created by PM on 08/11/2016.
  */
trait FatalEvent

case object BitcoinRPCConnectionError extends FatalEvent

case object BitcoinPeerConnectionError extends FatalEvent

case object TCPBindError extends FatalEvent

case object HTTPBindError extends FatalEvent


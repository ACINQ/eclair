package fr.acinq.eclair.blockchain.zmq

import akka.event.EventStream
import fr.acinq.bitcoin.{Block, Transaction}
import fr.acinq.eclair.blockchain.{NewBlock, NewTransaction}
import grizzled.slf4j.Logging
import org.zeromq.ZMsg

/**
  * Created by PM on 30/03/2017.
  */
class ZeroMQClient(addr: String, eventStream: EventStream) extends Logging {

  new Thread(new Runnable {
    override def run(): Unit = {
      import org.zeromq.ZMQ
      val context = ZMQ.context(1)
      //  Connect to weather server
      val subscriber = context.socket(ZMQ.SUB)
      subscriber.connect(addr)
      //subscriber.subscribe("hashblock".getBytes(ZMQ.CHARSET))
      //subscriber.subscribe("hashtx".getBytes(ZMQ.CHARSET))
      subscriber.subscribe("rawblock".getBytes(ZMQ.CHARSET))
      subscriber.subscribe("rawtx".getBytes(ZMQ.CHARSET))
      while (true) {
        val msg = ZMsg.recvMsg(subscriber)
        msg.popString() match {
          case "rawblock" =>
            val block = Block.read(msg.pop().getData)
            logger.debug(s"received blockid=${block.blockId}")
            eventStream.publish(NewBlock(block))
          case "rawtx" =>
            val tx = Transaction.read(msg.pop().getData)
            logger.debug(s"received txid=${tx.txid}")
            eventStream.publish(NewTransaction(tx))
          case _ => {}
        }
      }
      subscriber.close()
    }
  }).start()

}

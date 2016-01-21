package fr.acinq.eclair.channel

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * Created by PM on 08/09/2015.
 */
@RunWith(classOf[JUnitRunner])
class HTLCUpdateSpec extends TestHelper {

  "Node" must {

    "successfully receive an htlc in NORMAL_LOWPRIO" in {
      val (node, channelDesc0) = reachState_NOANCHOR(NORMAL_LOWPRIO)

      val (channelDesc1, r) = send_htlc(node, channelDesc0, 40000000)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)

      val channelDesc2 = send_fulfill_htlc(node, channelDesc1, r)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)
    }

    "successfully send an htlc in NORMAL_HIGHPRIO" in {
      val (node, channelDesc0) = reachState_WITHANCHOR(NORMAL_HIGHPRIO)

      val (channelDesc1, r) = receive_htlc(node, channelDesc0, 40000000)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_LOWPRIO)

      val channelDesc2 = receive_fulfill_htlc(node, channelDesc1, r)
      node ! CMD_GETSTATE
      expectMsg(NORMAL_HIGHPRIO)
    }

  }

}

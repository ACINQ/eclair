package fr.acinq.eclair.payment

import scala.concurrent.duration._
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{TestConstants, randomBytes32}
import fr.acinq.eclair.channel.{CLOSED, CMD_FAIL_HTLC, CMD_FULFILL_HTLC, CMD_SIGN, ChannelStateChanged, ChannelVersion, Commitments, DATA_NORMAL, NORMAL, SYNCING}
import fr.acinq.eclair.payment.CommandBuffer.{CommandAck, CommandSend}
import org.scalatest.FunSuite

class CommandBufferSpec extends FunSuite {
  test("store and relay commands using a multimap") {
    implicit val system: ActorSystem = ActorSystem("command-buffer-test")
    val nodeParams = TestConstants.Bob.nodeParams

    val channelId = randomBytes32
    val fulfill = CMD_FULFILL_HTLC(1, ByteVector32.Zeroes, commit = true)
    val fail = CMD_FAIL_HTLC(2, Left(ByteVector32.Zeroes))

    val commandBuffer1 = system.actorOf(Props(new CommandBuffer(nodeParams, TestProbe().ref)))
    commandBuffer1 ! CommandSend(channelId, fulfill)
    commandBuffer1 ! CommandSend(channelId, fail)

    val commitments = Commitments(ChannelVersion.STANDARD, null, null, channelFlags = 0x01.toByte, null, null, null, null,
      localNextHtlcId = 32L, remoteNextHtlcId = 4L, null, null, null, null, channelId = channelId)

    val normal = DATA_NORMAL(commitments, null, buried = true, None, null, None, None)

    val channel = TestProbe()

    commandBuffer1 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    // A multimap in CommandBuffer uses Sets as keys so messages may be send to channel in a different order, but this does not matter (?)
    channel.expectMsg(fulfill.copy(commit = false))
    channel.expectMsgType[CMD_FAIL_HTLC]
    channel.expectMsg(CMD_SIGN)

    commandBuffer1 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    // Pending relays were not cleared by channel
    channel.expectMsg(fulfill.copy(commit = false))
    channel.expectMsgType[CMD_FAIL_HTLC]
    channel.expectMsg(CMD_SIGN)

    commandBuffer1 ! CommandAck(channelId, fulfill.id)
    commandBuffer1 ! CommandAck(channelId, fail.id)
    commandBuffer1 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    channel.expectNoMsg(250 millis)

    // Load from database
    commandBuffer1 ! CommandSend(channelId, fulfill)
    commandBuffer1 ! CommandSend(channelId, fail)
    val commandBuffer2 = system.actorOf(Props(new CommandBuffer(nodeParams, TestProbe().ref)))
    commandBuffer2 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    channel.expectMsg(fulfill.copy(commit = false))
    channel.expectMsgType[CMD_FAIL_HTLC]
    channel.expectMsg(CMD_SIGN)

    commandBuffer2 ! CommandAck(channelId, fulfill.id)
    commandBuffer2 ! CommandAck(channelId, fail.id)
    commandBuffer2 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    channel.expectNoMsg(250 millis)

    // Pending relays for this channel were removed from multimap
    commandBuffer1 ! ChannelStateChanged(channel.ref, null, null, SYNCING, CLOSED, normal)
    commandBuffer1 ! ChannelStateChanged(channel.ref, null, null, SYNCING, NORMAL, normal)
    channel.expectNoMsg(250 millis)
  }
}

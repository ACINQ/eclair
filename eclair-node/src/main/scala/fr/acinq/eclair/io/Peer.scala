package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, LoggingFSM, PoisonPill, Props, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.{HandshakeCompleted, Listener, Serializer}
import fr.acinq.eclair.db.{JavaSerializer, SimpleDb, SimpleTypedDb}
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.router.SendRoutingState
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Features, Globals, NodeParams}

import scala.compat.Platform

// @formatter:off

case object Reconnect
//case class ChannelIdSwitch(previousId: Long, nextId: Long)

sealed trait OfflineChannel
case class BrandNewChannel(c: NewChannel) extends OfflineChannel
//case class ColdChannel(f: File) extends OfflineChannel
case class HotChannel(channelId: Long, a: ActorRef) extends OfflineChannel

sealed trait Data
case class DisconnectedData(offlineChannels: Seq[OfflineChannel]) extends Data
case class InitializingData(transport: ActorRef, offlineChannels: Seq[OfflineChannel]) extends Data
case class ConnectedData(transport: ActorRef, remoteInit: Init, channels: Map[Long, ActorRef]) extends Data

sealed trait State
case object DISCONNECTED extends State
case object INITIALIZING extends State
case object CONNECTED extends State

case class PeerRecord(id: PublicKey, address: Option[InetSocketAddress])

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class Peer(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) extends LoggingFSM[State, Data] {

  import Peer._

  val db = nodeParams.db
  val peerDb = makePeerDb(db)

  startWith(DISCONNECTED, DisconnectedData(Nil))

  when(DISCONNECTED) {
    case Event(c: ChannelRecord, d@DisconnectedData(offlineChannels)) if c.state.remotePubKey != remoteNodeId =>
      log.warning(s"received channel data for the wrong peer ${c.state.remotePubKey}")
      stay

    case Event(c: ChannelRecord, d@DisconnectedData(offlineChannels)) =>
      val (channel, _) = createChannel(nodeParams, null, c.id, false, 0) // TODO: fixme using a dedicated restore message
      channel ! INPUT_RESTORED(c.id, c.state)
      stay using d.copy(offlineChannels = offlineChannels :+ HotChannel(c.id, channel))

    case Event(c: NewChannel, d@DisconnectedData(offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(Reconnect, _) if address_opt.isDefined =>
      context.parent ! NewConnection(remoteNodeId, address_opt.get, None)
      stay

    case Event(HandshakeCompleted(transport, _), DisconnectedData(channels)) =>
      log.info(s"registering as a listener to $transport")
      transport ! Listener(self)
      context watch transport
      transport ! Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)
      goto(INITIALIZING) using InitializingData(transport, channels)
  }

  when(INITIALIZING) {
    case Event(c: NewChannel, d@InitializingData(_, offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(remoteInit: Init, InitializingData(transport, offlineChannels)) =>
      import fr.acinq.eclair.Features._
      log.info(s"$remoteNodeId has features: channelPublic=${channelPublic(remoteInit.localFeatures)} initialRoutingSync=${initialRoutingSync(remoteInit.localFeatures)}")
      if (Features.areFeaturesCompatible(nodeParams.localFeatures, remoteInit.localFeatures)) {
        if (Features.initialRoutingSync(remoteInit.localFeatures) != Unset) {
          router ! SendRoutingState(transport)
        }
        // let's bring existing/requested channels online
        val channels = offlineChannels.map {
          case BrandNewChannel(c) =>
            self ! c
            None
          case HotChannel(channelId, channel) =>
            channel ! INPUT_RECONNECTED(transport)
            Some((channelId -> channel))
        }.flatten.toMap
        goto(CONNECTED) using ConnectedData(transport, remoteInit, channels)
      } else {
        log.warning(s"incompatible features, disconnecting")
        transport ! PoisonPill
        stay
      }

    case Event(Terminated(actor), InitializingData(transport, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(channels)
  }

  when(CONNECTED) {
    case Event(c: NewChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis} and pushMsat=${c.pushMsat}")
      val temporaryChannelId = Platform.currentTime
      val (channel, localParams) = createChannel(nodeParams, transport, temporaryChannelId, funder = true, c.fundingSatoshis.toLong)
      channel ! INPUT_INIT_FUNDER(remoteNodeId, temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, localParams, remoteInit)
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg@FundingLocked(previousId, nextId, _), d@ConnectedData(_, _, channels)) if channels.contains(previousId) =>
      log.info(s"channel id switch: previousId=$previousId nextId=$nextId")
      val channel = channels(previousId)
      channel forward msg
      //TODO: what if nextIds are different
      stay using d.copy(channels = channels - previousId + (nextId -> channel))

    case Event(msg: HasTemporaryChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.temporaryChannelId) =>
      val channel = channels(msg.temporaryChannelId)
      channel forward msg
      stay

    case Event(msg: HasChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.channelId) =>
      val channel = channels(msg.channelId)
      channel forward msg
      stay

    case Event(msg: OpenChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"accepting a new channel to $remoteNodeId")
      val temporaryChannelId = msg.temporaryChannelId
      val (channel, localParams) = createChannel(nodeParams, transport, temporaryChannelId, funder = false, fundingSatoshis = msg.fundingSatoshis)
      channel ! INPUT_INIT_FUNDEE(remoteNodeId, temporaryChannelId, localParams, remoteInit)
      channel ! msg
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg: RoutingMessage, ConnectedData(transport, _, _)) if sender == router =>
      transport forward msg
      stay

    case Event(msg: RoutingMessage, _) =>
      router forward msg
      stay

    case Event(Terminated(actor), ConnectedData(transport, _, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      goto(DISCONNECTED) using DisconnectedData(channels.toSeq.map(c => HotChannel(c._1, c._2)))

    case Event(Terminated(actor), d@ConnectedData(transport, _, channels)) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      log.info(s"channel closed: channelId=$channelId")
      if (channels.size == 1) {
        log.info(s"that was the last channel open, closing the connection")
        transport ! PoisonPill
      }
      stay using d.copy(channels = channels - channelId)
  }

  def createChannel(nodeParams: NodeParams, transport: ActorRef, temporaryChannelId: Long, funder: Boolean, fundingSatoshis: Long): (ActorRef, LocalParams) = {
    val localParams = makeChannelParams(nodeParams, temporaryChannelId, defaultFinalScriptPubKey, funder, fundingSatoshis)
    val channel = context.actorOf(Channel.props(nodeParams, transport, watcher, router, relayer), s"channel-$temporaryChannelId")
    context watch channel
    (channel, localParams)
  }

}

object Peer {

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) = Props(new Peer(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, defaultFinalScriptPubKey))

  def generateKey(nodeParams: NodeParams, keyPath: Seq[Long]): PrivateKey = DeterministicWallet.derivePrivateKey(nodeParams.extendedPrivateKey, keyPath).privateKey

  def makeChannelParams(nodeParams: NodeParams, keyIndex: Long, defaultFinalScriptPubKey: BinaryData, isFunder: Boolean, fundingSatoshis: Long): LocalParams =
    LocalParams(
      dustLimitSatoshis = nodeParams.dustLimitSatoshis,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserveSatoshis = (nodeParams.reserveToFundingRatio * fundingSatoshis).toLong,
      htlcMinimumMsat = nodeParams.htlcMinimumMsat,
      feeratePerKw = nodeParams.feeratePerKw,
      toSelfDelay = nodeParams.delayBlocks,
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      fundingPrivKey = generateKey(nodeParams, keyIndex :: 0L :: Nil),
      revocationSecret = generateKey(nodeParams, keyIndex :: 1L :: Nil),
      paymentKey = generateKey(nodeParams, keyIndex :: 2L :: Nil),
      delayedPaymentKey = generateKey(nodeParams, keyIndex :: 3L :: Nil),
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      shaSeed = Crypto.sha256(generateKey(nodeParams, keyIndex :: 4L :: Nil).toBin), // TODO: check that
      isFunder = isFunder,
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures
    )

  def makePeerDb(db: SimpleDb): SimpleTypedDb[PublicKey, PeerRecord] = {
    def peerid2String(id: PublicKey) = s"peer-$id"

    def string2peerid(s: String) = if (s.startsWith("peer-")) Some(PublicKey(BinaryData(s.stripPrefix("peer-")))) else None

    new SimpleTypedDb[PublicKey, PeerRecord](
      peerid2String,
      string2peerid,
      new Serializer[PeerRecord] {
        override def serialize(t: PeerRecord): BinaryData = JavaSerializer.serialize(t)

        override def deserialize(bin: BinaryData): PeerRecord = JavaSerializer.deserialize[PeerRecord](bin)
      },
      db
    )
  }
}

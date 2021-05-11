/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.remote

import akka.actor.{ActorRef, ExtendedActorSystem}
import akka.serialization.Serialization
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.io.Switchboard.RouterPeerConf
import fr.acinq.eclair.io.{ClientSpawner, Peer, PeerConnection, Switchboard}
import fr.acinq.eclair.router.Router.{GossipDecision, RouterConf, SendChannelQuery}
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.QueryChannelRangeTlv.queryFlagsCodec
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Features}
import scodec._
import scodec.codecs._

import java.net.{InetAddress, InetSocketAddress}
import scala.concurrent.duration._

class EclairInternalsSerializer(val system: ExtendedActorSystem) extends ScodecSerializer(43, EclairInternalsSerializer.codec(system))

object EclairInternalsSerializer {

  trait RemoteTypes extends Serializable

  def finiteDurationCodec: Codec[FiniteDuration] = int64.xmap(_.milliseconds, _.toMillis)

  def iterable[A](codec: Codec[A]): Codec[Iterable[A]] = listOfN(uint16, codec).xmap(_.toIterable, _.toList)

  val routerConfCodec: Codec[RouterConf] = (
    ("randomizeRouteSelection" | bool(8)) ::
      ("channelExcludeDuration" | finiteDurationCodec) ::
      ("routerBroadcastInterval" | finiteDurationCodec) ::
      ("networkStatsRefreshInterval" | finiteDurationCodec) ::
      ("requestNodeAnnouncements" | bool(8)) ::
      ("encodingType" | discriminated[EncodingType].by(uint8)
        .typecase(0, provide(EncodingType.UNCOMPRESSED))
        .typecase(1, provide(EncodingType.COMPRESSED_ZLIB))) ::
      ("channelRangeChunkSize" | int32) ::
      ("channelQueryChunkSize" | int32) ::
      ("searchMaxFeeBase" | satoshi) ::
      ("searchMaxFeePct" | double) ::
      ("searchMaxRouteLength" | int32) ::
      ("searchMaxCltv" | int32.as[CltvExpiryDelta]) ::
      ("searchHeuristicsEnabled" | bool(8)) ::
      ("searchRatioBias" | double) ::
      ("searchRatioCltv" | double) ::
      ("searchRatioChannelAge" | double) ::
      ("searchRatioChannelCapacity" | double) ::
      ("mppMinPartAmount" | millisatoshi) ::
      ("mppMaxParts" | int32)).as[RouterConf]

  val overrideFeaturesListCodec: Codec[List[(PublicKey, Features)]] = listOfN(uint16, publicKey ~ variableSizeBytes(uint16, featuresCodec))

  val peerConnectionConfCodec: Codec[PeerConnection.Conf] = (
    ("authTimeout" | finiteDurationCodec) ::
      ("initTimeout" | finiteDurationCodec) ::
      ("pingInterval" | finiteDurationCodec) ::
      ("pingTimeout" | finiteDurationCodec) ::
      ("pingDisconnect" | bool(8)) ::
      ("maxRebroadcastDelay" | finiteDurationCodec)).as[PeerConnection.Conf]

  val peerConnectionDoSyncCodec: Codec[PeerConnection.DoSync] = bool(8).as[PeerConnection.DoSync]

  val peerConnectionKillReasonCodec: Codec[PeerConnection.KillReason] = discriminated[PeerConnection.KillReason].by(uint16)
    .typecase(0, provide(PeerConnection.KillReason.UserRequest))
    .typecase(1, provide(PeerConnection.KillReason.NoRemainingChannel))
    .typecase(2, provide(PeerConnection.KillReason.AllChannelsFail))
    .typecase(3, provide(PeerConnection.KillReason.ConnectionReplaced))

  val peerConnectionKillCodec: Codec[PeerConnection.Kill] = peerConnectionKillReasonCodec.as[PeerConnection.Kill]

  val lengthPrefixedInitCodec: Codec[Init] = variableSizeBytes(uint16, initCodec)
  val lengthPrefixedNodeAnnouncementCodec: Codec[NodeAnnouncement] = variableSizeBytes(uint16, nodeAnnouncementCodec)
  val lengthPrefixedChannelAnnouncementCodec: Codec[ChannelAnnouncement] = variableSizeBytes(uint16, channelAnnouncementCodec)
  val lengthPrefixedChannelUpdateCodec: Codec[ChannelUpdate] = variableSizeBytes(uint16, channelUpdateCodec)
  val lengthPrefixedAnnouncementCodec: Codec[AnnouncementMessage] = variableSizeBytes(uint16, lightningMessageCodec.downcast[AnnouncementMessage])
  val lengthPrefixedLightningMessageCodec: Codec[LightningMessage] = variableSizeBytes(uint16, lightningMessageCodec)

  def actorRefCodec(system: ExtendedActorSystem): Codec[ActorRef] = variableSizeBytes(uint16, utf8).xmap(
    (path: String) => system.provider.resolveActorRef(path),
    (actor: ActorRef) => Serialization.serializedActorPath(actor))

  val inetAddressCodec: Codec[InetAddress] = discriminated[InetAddress].by(uint8)
    .typecase(0, ipv4address)
    .typecase(1, ipv6address)

  val inetSocketAddressCodec: Codec[InetSocketAddress] = (inetAddressCodec ~ uint16).xmap({ case (addr, port) => new InetSocketAddress(addr, port) }, socketAddr => (socketAddr.getAddress, socketAddr.getPort))

  def connectionRequestCodec(system: ExtendedActorSystem): Codec[ClientSpawner.ConnectionRequest] = (
    ("address" | inetSocketAddressCodec) ::
      ("remoteNodeId" | publicKey) ::
      ("origin" | actorRefCodec(system))).as[ClientSpawner.ConnectionRequest]

  def initializeConnectionCodec(system: ExtendedActorSystem): Codec[PeerConnection.InitializeConnection] = (
    ("peer" | actorRefCodec(system)) ::
      ("chainHash" | bytes32) ::
      ("features" | variableSizeBytes(uint16, featuresCodec)) ::
      ("doSync" | bool(8))).as[PeerConnection.InitializeConnection]

  def connectionReadyCodec(system: ExtendedActorSystem): Codec[PeerConnection.ConnectionReady] = (
    ("peerConnection" | actorRefCodec(system)) ::
      ("remoteNodeId" | publicKey) ::
      ("address" | inetSocketAddressCodec) ::
      ("outgoing" | bool(8)) ::
      ("localInit" | lengthPrefixedInitCodec) ::
      ("remoteInit" | lengthPrefixedInitCodec)).as[PeerConnection.ConnectionReady]

  val optionQueryChannelRangeTlv: Codec[Option[QueryChannelRangeTlv]] = variableSizeBytes(uint16, optional(bool(8), variableSizeBytesLong(varintoverflow, queryFlagsCodec.upcast[QueryChannelRangeTlv])))

  def sendChannelQueryCodec(system: ExtendedActorSystem): Codec[SendChannelQuery] = (
    ("chainsHash" | bytes32) ::
      ("remoteNodeId" | publicKey) ::
      ("to" | actorRefCodec(system)) ::
      ("replacePrevious" | bool(8)) ::
      ("flags_opt" | optionQueryChannelRangeTlv)).as[SendChannelQuery]

  def peerRoutingMessageCodec(system: ExtendedActorSystem): Codec[PeerRoutingMessage] = (
    ("peerConnection" | actorRefCodec(system)) ::
      ("remoteNodeId" | publicKey) ::
      ("msg" | lengthPrefixedLightningMessageCodec.downcast[RoutingMessage])).as[PeerRoutingMessage]

  val singleChannelDiscoveredCodec: Codec[SingleChannelDiscovered] = (lengthPrefixedChannelAnnouncementCodec :: satoshi :: optional(bool(8), lengthPrefixedChannelUpdateCodec) :: optional(bool(8), lengthPrefixedChannelUpdateCodec)).as[SingleChannelDiscovered]

  val readAckCodec: Codec[TransportHandler.ReadAck] = lightningMessageCodec.upcast[Any].as[TransportHandler.ReadAck]

  def codec(system: ExtendedActorSystem): Codec[RemoteTypes] = discriminated[RemoteTypes].by(uint16)
    .typecase(0, provide(Switchboard.GetRouterPeerConf))
    .typecase(1, (routerConfCodec :: peerConnectionConfCodec).as[RouterPeerConf])
    .typecase(5, readAckCodec)
    .typecase(7, connectionRequestCodec(system))
    .typecase(10, (actorRefCodec(system) :: publicKey).as[PeerConnection.Authenticated])
    .typecase(11, initializeConnectionCodec(system))
    .typecase(12, connectionReadyCodec(system))
    .typecase(13, provide(PeerConnection.ConnectionResult.NoAddressFound))
    .typecase(14, inetSocketAddressCodec.as[PeerConnection.ConnectionResult.ConnectionFailed])
    .typecase(15, variableSizeBytes(uint16, utf8).as[PeerConnection.ConnectionResult.AuthenticationFailed])
    .typecase(16, variableSizeBytes(uint16, utf8).as[PeerConnection.ConnectionResult.InitializationFailed])
    .typecase(17, provide(PeerConnection.ConnectionResult.AlreadyConnected))
    .typecase(18, provide(PeerConnection.ConnectionResult.Connected))
    .typecase(19, actorRefCodec(system).as[Peer.ConnectionDown])
    .typecase(20, provide(Router.GetRoutingStateStreaming))
    .typecase(21, provide(Router.RoutingStateStreamingUpToDate))
    .typecase(22, sendChannelQueryCodec(system))
    .typecase(23, peerRoutingMessageCodec(system))
    .typecase(30, iterable(lengthPrefixedNodeAnnouncementCodec).as[NodesDiscovered])
    .typecase(31, lengthPrefixedNodeAnnouncementCodec.as[NodeUpdated])
    .typecase(32, publicKey.as[NodeLost])
    .typecase(33, iterable(singleChannelDiscoveredCodec).as[ChannelsDiscovered])
    .typecase(34, shortchannelid.as[ChannelLost])
    .typecase(35, iterable(lengthPrefixedChannelUpdateCodec).as[ChannelUpdatesReceived])
    .typecase(36, double.as[SyncProgress])
    .typecase(40, lengthPrefixedAnnouncementCodec.as[GossipDecision.Accepted])
    .typecase(41, lengthPrefixedAnnouncementCodec.as[GossipDecision.Duplicate])
    .typecase(42, lengthPrefixedAnnouncementCodec.as[GossipDecision.InvalidSignature])
    .typecase(43, lengthPrefixedNodeAnnouncementCodec.as[GossipDecision.NoKnownChannel])
    .typecase(44, lengthPrefixedChannelAnnouncementCodec.as[GossipDecision.ValidationFailure])
    .typecase(45, lengthPrefixedChannelAnnouncementCodec.as[GossipDecision.InvalidAnnouncement])
    .typecase(46, lengthPrefixedChannelAnnouncementCodec.as[GossipDecision.ChannelPruned])
    .typecase(47, lengthPrefixedChannelAnnouncementCodec.as[GossipDecision.ChannelClosing])
    .typecase(48, lengthPrefixedChannelUpdateCodec.as[GossipDecision.Stale])
    .typecase(49, lengthPrefixedChannelUpdateCodec.as[GossipDecision.NoRelatedChannel])
    .typecase(50, lengthPrefixedChannelUpdateCodec.as[GossipDecision.RelatedChannelPruned])
    .typecase(51, lengthPrefixedChannelAnnouncementCodec.as[GossipDecision.ChannelClosed])
    .typecase(52, peerConnectionKillCodec)
    .typecase(53, peerConnectionDoSyncCodec)

}
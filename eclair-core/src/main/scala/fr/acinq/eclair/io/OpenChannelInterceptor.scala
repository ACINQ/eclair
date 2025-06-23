/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.io

import akka.actor
import akka.actor.typed.eventstream.EventStream.Publish
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair.Features.Wumbo
import fr.acinq.eclair.blockchain.OnChainPubkeyCache
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.io.Peer.{OpenChannelResponse, SpawnChannelNonInitiator}
import fr.acinq.eclair.io.PendingChannelsRateLimiter.AddOrRejectChannel
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{Error, LiquidityAds, NodeAddress}
import fr.acinq.eclair.{AcceptOpenChannel, Features, InitFeature, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, InterceptOpenChannelResponse, Logs, NodeParams, RejectOpenChannel}
import scodec.bits.ByteVector

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag

/**
 * Child actor of a Peer that handles accepting or rejecting a channel open request initiated by a remote peer and
 * configuring local parameters for all open channel requests. It only handles one channel request at a time.
 * If a concurrent request comes while still evaluating a previous one, the later request is immediately rejected.
 *
 * Note: If the remote peer disconnects before the interceptor fails or continues the non-initiator flow, according to the
 * Lightning spec the flow should be canceled. Therefore any response sent by this actor with a different `peerConnection`
 * should be ignored and not forwarded to the remote peer.
 */
object OpenChannelInterceptor {

  // @formatter:off
  sealed trait Command

  sealed trait WaitForRequestCommands extends Command
  case class OpenChannelNonInitiator(remoteNodeId: PublicKey, open: Either[protocol.OpenChannel, protocol.OpenDualFundedChannel], localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], peerConnection: ActorRef[Any], peerAddress: NodeAddress) extends WaitForRequestCommands {
    val temporaryChannelId: ByteVector32 = open.fold(_.temporaryChannelId, _.temporaryChannelId)
    val fundingAmount: Satoshi = open.fold(_.fundingSatoshis, _.fundingAmount)
    val channelFlags: ChannelFlags = open.fold(_.channelFlags, _.channelFlags)
    val channelType_opt: Option[ChannelType] = open.fold(_.channelType_opt, _.channelType_opt)
  }
  case class OpenChannelInitiator(replyTo: ActorRef[OpenChannelResponse], remoteNodeId: PublicKey, open: Peer.OpenChannel, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]) extends WaitForRequestCommands

  private sealed trait CheckRateLimitsCommands extends Command
  private case class PendingChannelsRateLimiterResponse(response: PendingChannelsRateLimiter.Response) extends CheckRateLimitsCommands

  private case class WrappedPeerChannels(channels: Seq[Peer.ChannelInfo]) extends Command

  private sealed trait QueryPluginCommands extends Command
  private case class PluginOpenChannelResponse(pluginResponse: InterceptOpenChannelResponse) extends QueryPluginCommands
  private case object PluginTimeout extends QueryPluginCommands
  // @formatter:on

  def apply(peer: ActorRef[Any], nodeParams: NodeParams, remoteNodeId: PublicKey, wallet: OnChainPubkeyCache, pendingChannelsRateLimiter: ActorRef[PendingChannelsRateLimiter.Command], pluginTimeout: FiniteDuration = 1 minute): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        new OpenChannelInterceptor(peer, pendingChannelsRateLimiter, pluginTimeout, nodeParams, wallet, context).waitForRequest()
      }
    }

  def makeChannelParams(nodeParams: NodeParams, initFeatures: Features[InitFeature], upfrontShutdownScript_opt: Option[ByteVector], walletStaticPaymentBasepoint_opt: Option[PublicKey], isChannelOpener: Boolean, paysCommitTxFees: Boolean, dualFunded: Boolean, fundingAmount: Satoshi): LocalChannelParams = {
    LocalChannelParams(
      nodeParams.nodeId,
      nodeParams.channelKeyManager.newFundingKeyPath(isChannelOpener),
      initialRequestedChannelReserve_opt = if (dualFunded) None else Some((fundingAmount * nodeParams.channelConf.reserveToFundingRatio).max(nodeParams.channelConf.dustLimit)), // BOLT #2: make sure that our reserve is above our dust limit,
      isChannelOpener = isChannelOpener,
      paysCommitTxFees = paysCommitTxFees,
      upfrontShutdownScript_opt = upfrontShutdownScript_opt,
      walletStaticPaymentBasepoint = walletStaticPaymentBasepoint_opt,
      initFeatures = initFeatures
    )
  }

}

private class OpenChannelInterceptor(peer: ActorRef[Any],
                                     pendingChannelsRateLimiter: ActorRef[PendingChannelsRateLimiter.Command],
                                     pluginTimeout: FiniteDuration,
                                     nodeParams: NodeParams,
                                     wallet: OnChainPubkeyCache,
                                     context: ActorContext[OpenChannelInterceptor.Command]) {

  import OpenChannelInterceptor._

  private def waitForRequest(): Behavior[Command] = {
    receiveCommandMessage[WaitForRequestCommands](context, "waitForRequest") {
      case request: OpenChannelInitiator => sanityCheckInitiator(request)
      case request: OpenChannelNonInitiator => sanityCheckNonInitiator(request)
    }
  }

  private def sanityCheckInitiator(request: OpenChannelInitiator): Behavior[Command] = {
    if (request.open.fundingAmount >= Channel.MAX_FUNDING_WITHOUT_WUMBO && !request.localFeatures.hasFeature(Wumbo)) {
      request.replyTo ! OpenChannelResponse.Rejected(s"fundingAmount=${request.open.fundingAmount} is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING_WITHOUT_WUMBO} (see eclair.conf)")
      waitForRequest()
    } else if (request.open.fundingAmount >= Channel.MAX_FUNDING_WITHOUT_WUMBO && !request.remoteFeatures.hasFeature(Wumbo)) {
      request.replyTo ! OpenChannelResponse.Rejected(s"fundingAmount=${request.open.fundingAmount} is too big, the remote peer doesn't support wumbo")
      waitForRequest()
    } else {
      // If a channel type was provided, we directly use it instead of computing it based on local and remote features.
      val channelFlags = request.open.channelFlags_opt.getOrElse(nodeParams.channelConf.channelFlags)
      val channelType = request.open.channelType_opt.getOrElse(ChannelTypes.defaultFromFeatures(request.localFeatures, request.remoteFeatures, channelFlags.announceChannel))
      val dualFunded = Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.DualFunding)
      val upfrontShutdownScript = Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.UpfrontShutdownScript)
      // If we're purchasing liquidity, we expect our peer to contribute at least the amount we're purchasing, otherwise we'll cancel the funding attempt.
      val expectedFundingAmount = request.open.fundingAmount + request.open.requestFunding_opt.map(_.requestedAmount).getOrElse(0 sat)
      val localParams = createLocalParams(nodeParams, request.localFeatures, upfrontShutdownScript, channelType, isChannelOpener = true, paysCommitTxFees = true, dualFunded = dualFunded, expectedFundingAmount)
      peer ! Peer.SpawnChannelInitiator(request.replyTo, request.open, ChannelConfig.standard, channelType, localParams)
      waitForRequest()
    }
  }

  private def sanityCheckNonInitiator(request: OpenChannelNonInitiator): Behavior[Command] = {
    validateRemoteChannelType(request.temporaryChannelId, request.channelFlags, request.channelType_opt, request.localFeatures, request.remoteFeatures) match {
      case Right(_: ChannelTypes.Standard) =>
        context.log.warn("rejecting incoming channel: anchor outputs must be used for new channels")
        sendFailure("rejecting incoming channel: anchor outputs must be used for new channels", request)
        waitForRequest()
      case Right(_: ChannelTypes.StaticRemoteKey) if !nodeParams.channelConf.acceptIncomingStaticRemoteKeyChannels =>
        context.log.warn("rejecting static_remote_key incoming static_remote_key channels")
        sendFailure("rejecting incoming static_remote_key channel: anchor outputs must be used for new channels", request)
        waitForRequest()
      case Right(channelType) =>
        val dualFunded = Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.DualFunding)
        val upfrontShutdownScript = Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.UpfrontShutdownScript)
        // We only accept paying the commit fees if:
        //  - our peer supports on-the-fly funding, indicating that they're a mobile wallet
        //  - they are purchasing liquidity for this channel
        val nonInitiatorPaysCommitTxFees = request.channelFlags.nonInitiatorPaysCommitFees &&
          Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.OnTheFlyFunding) &&
          request.open.fold(_ => false, _.requestFunding_opt.isDefined)
        val localParams = createLocalParams(
          nodeParams,
          request.localFeatures,
          upfrontShutdownScript,
          channelType,
          isChannelOpener = false,
          paysCommitTxFees = nonInitiatorPaysCommitTxFees,
          dualFunded = dualFunded,
          fundingAmount = request.fundingAmount
        )
        checkRateLimits(request, channelType, localParams)
      case Left(ex) =>
        context.log.warn(s"ignoring remote channel open: ${ex.getMessage}")
        sendFailure(ex.getMessage, request)
        waitForRequest()
    }
  }

  private def checkRateLimits(request: OpenChannelNonInitiator, channelType: SupportedChannelType, localParams: LocalChannelParams): Behavior[Command] = {
    val adapter = context.messageAdapter[PendingChannelsRateLimiter.Response](PendingChannelsRateLimiterResponse)
    pendingChannelsRateLimiter ! AddOrRejectChannel(adapter, request.remoteNodeId, request.temporaryChannelId)
    receiveCommandMessage[CheckRateLimitsCommands](context, "checkRateLimits") {
      case PendingChannelsRateLimiterResponse(PendingChannelsRateLimiter.AcceptOpenChannel) =>
        checkLiquidityAdsRequest(request, channelType, localParams)
      case PendingChannelsRateLimiterResponse(PendingChannelsRateLimiter.ChannelRateLimited) =>
        context.log.warn(s"ignoring remote channel open: rate limited")
        sendFailure("rate limit reached", request)
        waitForRequest()
    }
  }

  /**
   * If an external plugin was configured, we forward the channel request for further analysis. Otherwise, we accept
   * the channel and honor the optional liquidity request only for on-the-fly funding where we enforce a single channel.
   */
  private def checkLiquidityAdsRequest(request: OpenChannelNonInitiator, channelType: SupportedChannelType, localParams: LocalChannelParams): Behavior[Command] = {
    nodeParams.pluginOpenChannelInterceptor match {
      case Some(plugin) => queryPlugin(plugin, request, localParams, ChannelConfig.standard, channelType)
      case None =>
        request.open.fold(_ => None, _.requestFunding_opt) match {
          case Some(requestFunding) if Features.canUseFeature(request.localFeatures, request.remoteFeatures, Features.OnTheFlyFunding) && localParams.paysCommitTxFees =>
            val addFunding = LiquidityAds.AddFunding(requestFunding.requestedAmount, nodeParams.liquidityAdsConfig.rates_opt)
            val accept = SpawnChannelNonInitiator(request.open, ChannelConfig.standard, channelType, Some(addFunding), localParams, request.peerConnection.toClassic)
            checkNoExistingChannel(request, accept)
          case _ =>
            // We don't honor liquidity ads for new channels: node operators should use plugin for that.
            peer ! SpawnChannelNonInitiator(request.open, ChannelConfig.standard, channelType, addFunding_opt = None, localParams, request.peerConnection.toClassic)
            waitForRequest()
        }
    }
  }

  /**
   * In some cases we want to reject additional channels when we already have one: it is usually better to splice the
   * existing channel instead of opening another one.
   */
  private def checkNoExistingChannel(request: OpenChannelNonInitiator, accept: SpawnChannelNonInitiator): Behavior[Command] = {
    peer ! Peer.GetPeerChannels(context.messageAdapter[Peer.PeerChannels](r => WrappedPeerChannels(r.channels)))
    receiveCommandMessage[WrappedPeerChannels](context, "checkNoExistingChannel") {
      case WrappedPeerChannels(channels) =>
        if (channels.forall(isClosing)) {
          peer ! accept
          waitForRequest()
        } else {
          context.log.warn("we already have an active channel, so we won't accept another one: our peer should request a splice instead")
          sendFailure("we already have an active channel: you should splice instead of requesting another channel", request)
          waitForRequest()
        }
    }
  }

  private def queryPlugin(plugin: InterceptOpenChannelPlugin, request: OpenChannelInterceptor.OpenChannelNonInitiator, localParams: LocalChannelParams, channelConfig: ChannelConfig, channelType: SupportedChannelType): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(PluginTimeout, pluginTimeout)
      val pluginResponseAdapter = context.messageAdapter[InterceptOpenChannelResponse](PluginOpenChannelResponse)
      plugin.openChannelInterceptor ! InterceptOpenChannelReceived(pluginResponseAdapter, request)
      receiveCommandMessage[QueryPluginCommands](context, "queryPlugin") {
        case PluginOpenChannelResponse(pluginResponse: AcceptOpenChannel) =>
          peer ! SpawnChannelNonInitiator(request.open, channelConfig, channelType, pluginResponse.addFunding_opt, localParams, request.peerConnection.toClassic)
          timers.cancel(PluginTimeout)
          waitForRequest()
        case PluginOpenChannelResponse(pluginResponse: RejectOpenChannel) =>
          sendFailure(pluginResponse.error.toAscii, request)
          timers.cancel(PluginTimeout)
          waitForRequest()
        case PluginTimeout =>
          context.log.error(s"timed out while waiting for plugin: ${plugin.name}")
          sendFailure("plugin timeout", request)
          waitForRequest()
      }
    }

  private def isClosing(channel: Peer.ChannelInfo): Boolean = channel.state match {
    case CLOSED => true
    case _ => channel.data match {
      case _: TransientChannelData => false
      case _: ChannelDataWithoutCommitments => false
      case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => false
      case _: DATA_WAIT_FOR_CHANNEL_READY => false
      case _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => false
      case _: DATA_WAIT_FOR_DUAL_FUNDING_READY => false
      case _: DATA_NORMAL => false
      case _: DATA_SHUTDOWN => true
      case _: DATA_NEGOTIATING => true
      case _: DATA_NEGOTIATING_SIMPLE => true
      case _: DATA_CLOSING => true
      case _: DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT => true
    }
  }

  private def sendFailure(error: String, request: OpenChannelNonInitiator): Unit = {
    peer ! Peer.OutgoingMessage(Error(request.temporaryChannelId, error), request.peerConnection.toClassic)
    context.system.eventStream ! Publish(ChannelAborted(actor.ActorRef.noSender, request.remoteNodeId, request.temporaryChannelId))
  }

  private def receiveCommandMessage[B <: Command : ClassTag](context: ActorContext[Command], stateName: String)(f: B => Behavior[Command]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case m: B => f(m)
      case o: OpenChannelInitiator =>
        o.replyTo ! OpenChannelResponse.Rejected("concurrent request rejected")
        Behaviors.same
      case o: OpenChannelNonInitiator =>
        context.log.warn(s"ignoring remote channel open: concurrent request rejected")
        sendFailure("concurrent request rejected", o)
        Behaviors.same
      case m =>
        context.log.error(s"$stateName: received unhandled message $m")
        Behaviors.same
    }
  }

  private def validateRemoteChannelType(temporaryChannelId: ByteVector32, channelFlags: ChannelFlags, remoteChannelType_opt: Option[ChannelType], localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]): Either[ChannelException, SupportedChannelType] = {
    remoteChannelType_opt match {
      // remote explicitly specifies a channel type: we check whether we want to allow it
      case Some(remoteChannelType) => ChannelTypes.areCompatible(localFeatures, remoteChannelType) match {
        case Some(acceptedChannelType) => Right(acceptedChannelType)
        case None => Left(InvalidChannelType(temporaryChannelId, ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures, channelFlags.announceChannel), remoteChannelType))
      }
      // Bolt 2: if `option_channel_type` is negotiated: MUST set `channel_type`
      case None if Features.canUseFeature(localFeatures, remoteFeatures, Features.ChannelType) => Left(MissingChannelType(temporaryChannelId))
      // remote doesn't specify a channel type: we use spec-defined defaults
      case None => Right(ChannelTypes.defaultFromFeatures(localFeatures, remoteFeatures, channelFlags.announceChannel))
    }
  }

  private def createLocalParams(nodeParams: NodeParams, initFeatures: Features[InitFeature], upfrontShutdownScript: Boolean, channelType: SupportedChannelType, isChannelOpener: Boolean, paysCommitTxFees: Boolean, dualFunded: Boolean, fundingAmount: Satoshi): LocalChannelParams = {
    makeChannelParams(
      nodeParams, initFeatures,
      // Note that if our bitcoin node is configured to use taproot, this will generate a taproot script.
      // If our peer doesn't support option_shutdown_anysegwit, the channel open will fail.
      // This is fine: "serious" nodes should support option_shutdown_anysegwit, and if we want to use taproot, we
      // most likely don't want to open channels with nodes that don't support it. 
      if (upfrontShutdownScript) Some(Script.write(wallet.getReceivePublicKeyScript(renew = true))) else None,
      if (channelType.paysDirectlyToWallet) Some(wallet.getP2wpkhPubkey(renew = true)) else None,
      isChannelOpener = isChannelOpener,
      paysCommitTxFees = paysCommitTxFees,
      dualFunded = dualFunded,
      fundingAmount
    )
  }

}

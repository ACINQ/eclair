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
import akka.actor.Status
import akka.actor.typed.eventstream.EventStream.Publish
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{BtcDouble, ByteVector32, Satoshi, Script}
import fr.acinq.eclair.Features.Wumbo
import fr.acinq.eclair.blockchain.OnchainPubkeyCache
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.io.Peer.SpawnChannelNonInitiator
import fr.acinq.eclair.io.PendingChannelsRateLimiter.AddOrRejectChannel
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.Error
import fr.acinq.eclair.{AcceptOpenChannel, CltvExpiryDelta, Features, InitFeature, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, InterceptOpenChannelResponse, MilliSatoshi, NodeParams, RejectOpenChannel, ToMilliSatoshiConversion}
import scodec.bits.ByteVector

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.reflect.ClassTag

/**
 * Child actor of a Peer that handles accepting or rejecting a channel open request initiated by a remote peer and
 * configuring local parameters for all open channel requests. It only handles one channel request at a time.
 * If a concurrent request comes while still evaluating a previous one, the later request is immediately rejected.
 *
 * Note: If the remote peer disconnects before the plugin fails or continues the non-initiator flow, according to the
 * Lightning spec the flow should be canceled. Therefore any response sent by this actor with a different `peerConnection`
 * should be ignored and not forwarded to the remote peer.
 */
object OpenChannelInterceptor {

  // @formatter:off
  sealed trait Command

  sealed trait WaitForRequestCommands extends Command
  case class OpenChannelNonInitiator(remoteNodeId: PublicKey, open: Either[protocol.OpenChannel, protocol.OpenDualFundedChannel], localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature], peerConnection: ActorRef[Any]) extends WaitForRequestCommands {
    val temporaryChannelId: ByteVector32 = open.fold(_.temporaryChannelId, _.temporaryChannelId)
    val fundingAmount: Satoshi = open.fold(_.fundingSatoshis, _.fundingAmount)
    val channelFlags: ChannelFlags = open.fold(_.channelFlags, _.channelFlags)
    val channelType_opt: Option[ChannelType] = open.fold(_.channelType_opt, _.channelType_opt)
  }
  case class OpenChannelInitiator(replyTo: ActorRef[Any], remoteNodeId: PublicKey, open: Peer.OpenChannel, localFeatures: Features[InitFeature], remoteFeatures: Features[InitFeature]) extends WaitForRequestCommands

  private sealed trait CheckRateLimitsCommands extends Command
  private case class PendingChannelsRateLimiterResponse(response: PendingChannelsRateLimiter.Response) extends CheckRateLimitsCommands

  private sealed trait QueryPluginCommands extends Command
  private case class PluginOpenChannelResponse(pluginResponse: InterceptOpenChannelResponse) extends QueryPluginCommands
  private case object PluginTimeout extends QueryPluginCommands

  private sealed trait ErrorResponse
  private object RateLimitError extends ErrorResponse {
    override def toString: String = "rate limit reached"
  }
  private case class ChannelExceptionError(ex: ChannelException) extends ErrorResponse {
    override def toString: String = ex.getMessage
  }

  private object ConcurrentRequestError extends ErrorResponse {
    override def toString: String = "concurrent request rejected"
  }
  private object PluginTimeoutError extends ErrorResponse {
    override def toString: String = "plugin timeout"
  }
  private case class PluginRejectedError(error: Error) extends ErrorResponse {
    override def toString: String = error.toAscii
  }
  // @formatter:on

  /** DefaultParams are a subset of ChannelData.LocalParams that can be modified by an InterceptOpenChannelPlugin */
  case class DefaultParams(dustLimit: Satoshi,
                           maxHtlcValueInFlightMsat: MilliSatoshi,
                           htlcMinimum: MilliSatoshi,
                           toSelfDelay: CltvExpiryDelta,
                           maxAcceptedHtlcs: Int)

  def apply(peer: ActorRef[Any], nodeParams: NodeParams, wallet: OnchainPubkeyCache, pendingChannelsRateLimiter: ActorRef[PendingChannelsRateLimiter.Command], pluginTimeout: FiniteDuration = 1 minute): Behavior[Command] =
    Behaviors.setup {
      context => new OpenChannelInterceptor(peer, pendingChannelsRateLimiter, pluginTimeout, nodeParams, wallet, context).waitForRequest()
    }

  def makeChannelParams(nodeParams: NodeParams, initFeatures: Features[InitFeature], upfrontShutdownScript_opt: Option[ByteVector], walletStaticPaymentBasepoint_opt: Option[PublicKey], isInitiator: Boolean, dualFunded: Boolean, fundingAmount: Satoshi, unlimitedMaxHtlcValueInFlight: Boolean): LocalParams = {
    val maxHtlcValueInFlightMsat = if (unlimitedMaxHtlcValueInFlight) {
      // We don't want to impose limits on the amount in flight, typically to allow fully emptying the channel.
      21e6.btc.toMilliSatoshi
    } else {
      // NB: when we're the initiator, we don't know yet if the remote peer will contribute to the funding amount, so
      // the percentage-based value may be underestimated. That's ok, this is a security parameter so it makes sense to
      // base it on the amount that we're contributing instead of the total funding amount.
      nodeParams.channelConf.maxHtlcValueInFlightMsat.min(fundingAmount * nodeParams.channelConf.maxHtlcValueInFlightPercent / 100)
    }
    LocalParams(
      nodeParams.nodeId,
      nodeParams.channelKeyManager.newFundingKeyPath(isInitiator), // we make sure that initiator and non-initiator key paths end differently
      dustLimit = nodeParams.channelConf.dustLimit,
      maxHtlcValueInFlightMsat = maxHtlcValueInFlightMsat,
      requestedChannelReserve_opt = if (dualFunded) None else Some((fundingAmount * nodeParams.channelConf.reserveToFundingRatio).max(nodeParams.channelConf.dustLimit)), // BOLT #2: make sure that our reserve is above our dust limit
      htlcMinimum = nodeParams.channelConf.htlcMinimum,
      toSelfDelay = nodeParams.channelConf.toRemoteDelay, // we choose their delay
      maxAcceptedHtlcs = nodeParams.channelConf.maxAcceptedHtlcs,
      isInitiator = isInitiator,
      upfrontShutdownScript_opt = upfrontShutdownScript_opt,
      walletStaticPaymentBasepoint = walletStaticPaymentBasepoint_opt,
      initFeatures = initFeatures
    )
  }

}

/**
 * @param peer parent Peer actor
 * @param pendingChannelsRateLimiter actor that will tell us whether we should accept a channel request
 * @param pluginTimeout timeout for the plugin to respond
 */
private class OpenChannelInterceptor(peer: ActorRef[Any],
                                     pendingChannelsRateLimiter: ActorRef[PendingChannelsRateLimiter.Command],
                                     pluginTimeout: FiniteDuration,
                                     nodeParams: NodeParams,
                                     wallet: OnchainPubkeyCache,
                                     context: ActorContext[OpenChannelInterceptor.Command]) {

  import OpenChannelInterceptor._

  private def waitForRequest(): Behavior[Command] = {
    receiveCommandMessage[WaitForRequestCommands](context, "waitForRequest") {
      case initiator: OpenChannelInitiator => sanityCheckInitiator(initiator)
      case nonInitiator: OpenChannelNonInitiator => checkRateLimits(nonInitiator)
    }
  }

  private def sanityCheckInitiator(initiator: OpenChannelInitiator): Behavior[Command] = {
    if (initiator.open.fundingAmount >= Channel.MAX_FUNDING && !initiator.localFeatures.hasFeature(Wumbo)) {
      initiator.replyTo ! Status.Failure(new RuntimeException(s"fundingAmount=${initiator.open.fundingAmount} is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)"))
      waitForRequest()
    } else if (initiator.open.fundingAmount >= Channel.MAX_FUNDING && !initiator.remoteFeatures.hasFeature(Wumbo)) {
      initiator.replyTo ! Status.Failure(new RuntimeException(s"fundingAmount=${initiator.open.fundingAmount} is too big, the remote peer doesn't support wumbo"))
      waitForRequest()
    } else if (initiator.open.fundingAmount > nodeParams.channelConf.maxFundingSatoshis) {
      initiator.replyTo ! Status.Failure(new RuntimeException(s"fundingAmount=${initiator.open.fundingAmount} is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)"))
      waitForRequest()
    } else {
      // If a channel type was provided, we directly use it instead of computing it based on local and remote features.
      val channelFlags = initiator.open.channelFlags_opt.getOrElse(nodeParams.channelConf.channelFlags)
      val channelType = initiator.open.channelType_opt.getOrElse(ChannelTypes.defaultFromFeatures(initiator.localFeatures, initiator.remoteFeatures, channelFlags.announceChannel))
      val dualFunded = Features.canUseFeature(initiator.localFeatures, initiator.remoteFeatures, Features.DualFunding)
      val upfrontShutdownScript = Features.canUseFeature(initiator.localFeatures, initiator.remoteFeatures, Features.UpfrontShutdownScript)
      val localParams = createLocalParams(nodeParams, initiator.localFeatures, upfrontShutdownScript, channelType, isInitiator = true, dualFunded = dualFunded, initiator.open.fundingAmount, initiator.open.disableMaxHtlcValueInFlight)
      peer ! Peer.SpawnChannelInitiator(initiator.open, ChannelConfig.standard, channelType, localParams, initiator.replyTo.toClassic)
      waitForRequest()
    }
  }

  private def checkRateLimits(nonInitiator: OpenChannelNonInitiator): Behavior[Command] = {
    val adapter = context.messageAdapter[PendingChannelsRateLimiter.Response](PendingChannelsRateLimiterResponse)
    pendingChannelsRateLimiter ! AddOrRejectChannel(adapter, nonInitiator.remoteNodeId, nonInitiator.temporaryChannelId)
    receiveCommandMessage[CheckRateLimitsCommands](context, "checkRateLimits") {
      case PendingChannelsRateLimiterResponse(PendingChannelsRateLimiter.AcceptOpenChannel) =>
        validateRemoteChannelType(nonInitiator.temporaryChannelId, nonInitiator.channelFlags, nonInitiator.channelType_opt, nonInitiator.localFeatures, nonInitiator.remoteFeatures) match {
          case Right(channelType) =>
            val dualFunded = Features.canUseFeature(nonInitiator.localFeatures, nonInitiator.remoteFeatures, Features.DualFunding)
            val upfrontShutdownScript = Features.canUseFeature(nonInitiator.localFeatures, nonInitiator.remoteFeatures, Features.UpfrontShutdownScript)
            val localParams = createLocalParams(nodeParams, nonInitiator.localFeatures, upfrontShutdownScript, channelType, isInitiator = false, dualFunded = dualFunded, nonInitiator.fundingAmount, disableMaxHtlcValueInFlight = false)
            nodeParams.pluginOpenChannelInterceptor match {
              case Some(plugin) => queryPlugin(plugin, nonInitiator, localParams, ChannelConfig.standard, channelType)
              case None =>
                peer ! SpawnChannelNonInitiator(nonInitiator.open, ChannelConfig.standard, channelType, localParams, nonInitiator.peerConnection.toClassic)
                waitForRequest()
            }
          case Left(ex) =>
            context.log.warn(s"ignoring remote channel open: ${ex.getMessage}")
            sendFailure(ChannelExceptionError(ex), nonInitiator)
            waitForRequest()
        }
      case PendingChannelsRateLimiterResponse(PendingChannelsRateLimiter.ChannelRateLimited) =>
        context.log.warn(s"ignoring remote channel open: rate limited")
        sendFailure(RateLimitError, nonInitiator)
        waitForRequest()
    }
  }

  private def queryPlugin(plugin: InterceptOpenChannelPlugin, nonInitiator: OpenChannelInterceptor.OpenChannelNonInitiator, localParams: LocalParams, channelConfig: ChannelConfig, channelType: SupportedChannelType): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(PluginTimeout, pluginTimeout)
      val pluginResponseAdapter = context.messageAdapter[InterceptOpenChannelResponse](PluginOpenChannelResponse)
      val defaultParams = DefaultParams(localParams.dustLimit, localParams.maxHtlcValueInFlightMsat, localParams.htlcMinimum, localParams.toSelfDelay, localParams.maxAcceptedHtlcs)
      plugin.openChannelInterceptor ! InterceptOpenChannelReceived(pluginResponseAdapter, nonInitiator, defaultParams)
      receiveCommandMessage[QueryPluginCommands](context, "queryPlugin") {
        case PluginOpenChannelResponse(pluginResponse: AcceptOpenChannel) =>
          val localParams1 = updateLocalParams(localParams, pluginResponse.defaultParams)
          peer ! SpawnChannelNonInitiator(nonInitiator.open, channelConfig, channelType, localParams1, nonInitiator.peerConnection.toClassic)
          waitForRequest()
        case PluginOpenChannelResponse(pluginResponse: RejectOpenChannel) =>
          sendFailure(PluginRejectedError(pluginResponse.error), nonInitiator)
          waitForRequest()
        case PluginTimeout =>
          context.log.error(s"timed out while waiting for plugin: ${plugin.name}")
          sendFailure(PluginTimeoutError, nonInitiator)
          waitForRequest()
      }
    }

  private def sendFailure(error: ErrorResponse, nonInitiator: OpenChannelNonInitiator): Unit = {
    peer ! Peer.OutgoingMessage(Error(nonInitiator.temporaryChannelId, error.toString), nonInitiator.peerConnection.toClassic)
    context.system.eventStream ! Publish(ChannelAborted(actor.ActorRef.noSender, nonInitiator.remoteNodeId, nonInitiator.temporaryChannelId))
  }

  private def receiveCommandMessage[B <: Command : ClassTag](context: ActorContext[Command], stateName: String)(f: B => Behavior[Command]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case m: B => f(m)
      case o: OpenChannelInitiator =>
        o.replyTo ! Status.Failure(new RuntimeException("concurrent request rejected"))
        Behaviors.same
      case o: OpenChannelNonInitiator =>
        context.log.warn(s"ignoring remote channel open: concurrent request rejected")
        sendFailure(ConcurrentRequestError, o)
        Behaviors.same
      case m => context.log.error(s"$stateName: received unhandled message $m")
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

  private def createLocalParams(nodeParams: NodeParams, initFeatures: Features[InitFeature], upfrontShutdownScript: Boolean, channelType: SupportedChannelType, isInitiator: Boolean, dualFunded: Boolean, fundingAmount: Satoshi, disableMaxHtlcValueInFlight: Boolean): LocalParams = {
    val pubkey_opt = if (upfrontShutdownScript || channelType.paysDirectlyToWallet) Some(wallet.getP2wpkhPubkey()) else None
    makeChannelParams(
      nodeParams, initFeatures,
      if (upfrontShutdownScript) Some(Script.write(Script.pay2wpkh(pubkey_opt.get))) else None,
      if (channelType.paysDirectlyToWallet) Some(pubkey_opt.get) else None,
      isInitiator = isInitiator,
      dualFunded = dualFunded,
      fundingAmount,
      disableMaxHtlcValueInFlight
    )
  }

  private def updateLocalParams(localParams: LocalParams, defaultParams: DefaultParams): LocalParams = {
    localParams.copy(
      dustLimit = defaultParams.dustLimit,
      maxHtlcValueInFlightMsat = defaultParams.maxHtlcValueInFlightMsat,
      htlcMinimum = defaultParams.htlcMinimum,
      toSelfDelay = defaultParams.toSelfDelay,
      maxAcceptedHtlcs = defaultParams.maxAcceptedHtlcs
    )
  }

}

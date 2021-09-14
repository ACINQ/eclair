package fr.acinq.eclair.balance

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.balance.BalanceActor._
import fr.acinq.eclair.balance.CheckBalance.GlobalBalance
import fr.acinq.eclair.balance.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.Utxo
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.Databases
import grizzled.slf4j.Logger
import org.json4s.JsonAST.JInt

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object BalanceActor {

  // @formatter:off
  sealed trait Command
  private final case object TickBalance extends Command
  final case class GetGlobalBalance(replyTo: ActorRef[Try[GlobalBalance]], channels: Map[ByteVector32, HasCommitments]) extends Command
  private final case class WrappedChannels(wrapped: ChannelsListener.GetChannelsResponse) extends Command
  private final case class WrappedGlobalBalance(wrapped: Try[GlobalBalance]) extends Command
  private final case class WrappedUtxoInfo(wrapped: Try[UtxoInfo]) extends Command
  // @formatter:on

  def apply(db: Databases, bitcoinClient: BitcoinCoreClient, channelsListener: ActorRef[ChannelsListener.GetChannels], interval: FiniteDuration)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(TickBalance, interval)
        new BalanceActor(context, db, bitcoinClient, channelsListener).apply(refBalance_opt = None)
      }
    }
  }

  final case class UtxoInfo(utxos: Seq[Utxo], ancestorCount: Map[ByteVector32, Long])

  def checkUtxos(bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Future[UtxoInfo] = {

    def getUnconfirmedAncestorCount(utxo: Utxo): Future[(ByteVector32, Long)] = bitcoinClient.rpcClient.invoke("getmempoolentry", utxo.txid).map(json => {
      val JInt(ancestorCount) = json \ "ancestorcount"
      (utxo.txid, ancestorCount.toLong)
    }).recover {
      case ex: Throwable =>
        // a bit hackish but we don't need the actor context for this simple log
        val log = Logger(classOf[BalanceActor])
        log.warn(s"could not retrieve unconfirmed ancestor count for txId=${utxo.txid} amount=${utxo.amount}:", ex)
        (utxo.txid, 0)
    }

    def getUnconfirmedAncestorCountMap(utxos: Seq[Utxo]): Future[Map[ByteVector32, Long]] = Future.sequence(utxos.filter(_.confirmations == 0).map(getUnconfirmedAncestorCount)).map(_.toMap)

    for {
      utxos <- bitcoinClient.listUnspent()
      ancestorCount <- getUnconfirmedAncestorCountMap(utxos)
    } yield UtxoInfo(utxos, ancestorCount)
  }

}

private class BalanceActor(context: ActorContext[Command],
                           db: Databases,
                           bitcoinClient: BitcoinCoreClient,
                           channelsListener: ActorRef[ChannelsListener.GetChannels])(implicit ec: ExecutionContext) {

  private val log = context.log

  def apply(refBalance_opt: Option[GlobalBalance]): Behavior[Command] = Behaviors.receiveMessage {
    case TickBalance =>
      log.debug("checking balance...")
      channelsListener ! ChannelsListener.GetChannels(context.messageAdapter[ChannelsListener.GetChannelsResponse](WrappedChannels))
      context.pipeToSelf(checkUtxos(bitcoinClient))(WrappedUtxoInfo)
      Behaviors.same
    case WrappedChannels(res) =>
      context.pipeToSelf(CheckBalance.computeGlobalBalance(res.channels, db, bitcoinClient))(WrappedGlobalBalance)
      Behaviors.same
    case WrappedGlobalBalance(res) =>
      res match {
        case Success(result) =>
          log.info("current balance: total={} onchain.confirmed={} onchain.unconfirmed={} offchain={}", result.total.toDouble, result.onChain.confirmed.toDouble, result.onChain.unconfirmed.toDouble, result.offChain.total.toDouble)
          log.debug("current balance details : {}", result)
          Metrics.GlobalBalance.withoutTags().update(result.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnchainConfirmed).update(result.onChain.confirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.OnchainUnconfirmed).update(result.onChain.unconfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForFundingConfirmed).update(result.offChain.waitForFundingConfirmed.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForFundingLocked).update(result.offChain.waitForFundingLocked.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.normal).update(result.offChain.normal.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.shutdown).update(result.offChain.shutdown.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingLocal).update(result.offChain.closing.localCloseBalance.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingRemote).update(result.offChain.closing.remoteCloseBalance.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.closingUnknown).update(result.offChain.closing.unknownCloseBalance.total.toMilliBtc.toDouble)
          Metrics.GlobalBalanceDetailed.withTag(Tags.BalanceType, Tags.BalanceTypes.Offchain).withTag(Tags.OffchainState, Tags.OffchainStates.waitForPublishFutureCommitment).update(result.offChain.waitForPublishFutureCommitment.toMilliBtc.toDouble)
          refBalance_opt match {
            case Some(refBalance) =>
              val normalizedValue = 100 + (if (refBalance.total.toSatoshi.toLong > 0) (result.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong) * 1000D / refBalance.total.toSatoshi.toLong else 0)
              val diffValue = result.total.toSatoshi.toLong - refBalance.total.toSatoshi.toLong
              log.info("relative balance: current={} reference={} normalized={} diff={}", result.total.toDouble, refBalance.total.toDouble, normalizedValue, diffValue)
              Metrics.GlobalBalanceNormalized.withoutTags().update(normalizedValue)
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.plus).update(diffValue.max(0).toDouble)
              Metrics.GlobalBalanceDiff.withTag(Tags.DiffSign, Tags.DiffSigns.minus).update((-diffValue).max(0).toDouble)
              Behaviors.same
            case None =>
              log.info("using balance={} as reference", result.total.toDouble)
              apply(Some(result))
          }
        case Failure(t) =>
          log.warn("could not compute balance: ", t)
          Behaviors.same
      }
    case GetGlobalBalance(replyTo, channels) =>
      CheckBalance.computeGlobalBalance(channels, db, bitcoinClient) onComplete (replyTo ! _)
      Behaviors.same
    case WrappedUtxoInfo(res) =>
      res match {
        case Success(UtxoInfo(utxos: Seq[Utxo], ancestorCount: Map[ByteVector32, Long])) =>
          val filteredByStatus: Map[String, Seq[Utxo]] = Map(
            Monitoring.Tags.UtxoStatuses.Confirmed -> utxos.filter(utxo => utxo.confirmations > 0),
            // We cannot create chains of unconfirmed transactions with more than 25 elements, so we ignore such utxos.
            Monitoring.Tags.UtxoStatuses.Unconfirmed -> utxos.filter(utxo => utxo.confirmations == 0 && ancestorCount.getOrElse(utxo.txid, 1L) < 25),
            Monitoring.Tags.UtxoStatuses.Safe -> utxos.filter(utxo => utxo.safe),
            Monitoring.Tags.UtxoStatuses.Unsafe -> utxos.filter(utxo => !utxo.safe),
          )
          filteredByStatus.foreach {
            case (status, filteredUtxos) =>
              val amount = filteredUtxos.map(_.amount.toDouble).sum
              log.info(s"we have ${filteredUtxos.length} $status utxos ($amount mBTC)")
              Monitoring.Metrics.UtxoCount.withTag(Monitoring.Tags.UtxoStatus, status).update(filteredUtxos.length)
              Monitoring.Metrics.BitcoinBalance.withTag(Monitoring.Tags.UtxoStatus, status).update(amount)
          }
        case Failure(t) =>
          log.warn("could not check utxos: ", t)
      }
      Behaviors.same
  }
}

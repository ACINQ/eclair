package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef,Props}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData,MilliSatoshi, Satoshi}
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.channel._
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.transactions.{IN,OUT}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure,Success}


class AuditRequest(auditLogger: ActorRef, register: ActorRef, channelsDb: ChannelsDb) extends Actor with ActorLogging {

  var channelCount: Int=0
  var returnMessage: Seq[RECONCILE]=Seq.empty
  var infoReturn: GET_INFO = GET_INFO()
  var caller: ActorRef=self

  override def receive: Receive = {
    case CHANNEL_RECONCILE_ERRORS =>
      caller=sender() //message forwarded so will be the serviceapi
      // get all channels
      val channels=channelsDb.listChannels(true)
      channelCount=channels.size
      channels.foreach{c=>
        context.parent ! CHANNEL_RECONCILE(c.channelId)
      }
    case m: RECONCILE =>
      log.info("RecReceived")
      channelCount-=1
      if(!m.all && m.state!=CLOSED) returnMessage = returnMessage :+ m

      if(channelCount==0) {
        caller ! returnMessage
        context.stop(self)
      }

    case CHANNEL_GET_INFO =>
      caller=sender() //message forwarded so will be the serviceapi
      // get all channels
      val channels=channelsDb.listChannels(true) 
      channelCount=channels.size
      channels.foreach{c=>
        context.parent ! CHANNEL_BALANCE(c.channelId)
      }

    case a: AuditReturn =>
      channelCount-=1
      
      infoReturn=new GET_INFO(channelBalances=infoReturn.channelBalances+a.channelBalances,
          notNormalChannelBalances=if(a.state==NORMAL) infoReturn.notNormalChannelBalances else infoReturn.notNormalChannelBalances+a.channelBalances,
          infoReturn.channelCount + (a.state -> (infoReturn.channelCount(a.state)+1)),
          infoReturn.localCommitiment+a.localCommitiment
          )

      if(channelCount==0) {
        caller ! infoReturn
        context.stop(self)
      }
  }
}
object AuditRequest {
  def props(auditLogger: ActorRef, register: ActorRef, channelsDb: ChannelsDb) = Props(classOf[AuditRequest], auditLogger, register,channelsDb)
}

class AuditBalances(auditLogger: ActorRef, register: ActorRef, channelsDb: ChannelsDb) extends Actor with ActorLogging {
  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timeout = Timeout(60 seconds)
  var requestCount: Int=0
  override def receive: Receive = {
    case CHANNEL_BALANCE(channelId) =>
      val result=getBalances(channelId: BinaryData)
      result pipeTo sender()

    case CHANNEL_RECONCILE(channelId) =>
      val result=getBalances(channelId: BinaryData)
      val returnMessage = result.map {
        case a:AuditReturn =>
          val local=a.channelBalances.amount.amount==a.localCommitiment.toLocalmSat
          val htlc=a.channelBalances.htlcAmount.amount==a.localCommitiment.inflightOutHtlc
          import a.channelBalances._
          val dbRec=amount.amount + htlcAmount.amount - sentMsat.amount - receivedMsat.amount - rounding.amount + penalty*1000 == -(btcAmount-btcFee)*1000L
          
          val dbToCommitRec=if(a.state!=CLOSED){
            amount.amount==a.localCommitiment.toLocalmSat &&
              htlcAmount.amount==a.localCommitiment.inflightOutHtlc &&
              maxLocalHTLCId < a.localCommitiment.nextHtlcId // Can't check equal as could be failed ones that we did not audit 
          } else {
            delayed==0 && amount.amount==0 && htlcAmount.amount==0
          }
          RECONCILE(channelId,a.state.toString(), dbRec && dbToCommitRec, dbRec, dbToCommitRec)
        case _ =>
          RECONCILE(channelId,"Unknown", false,false,false)
      } recoverWith {case _ => Future(RECONCILE(channelId,"Unknown2", false,false,false)) }
      returnMessage pipeTo sender()

    case m@CHANNEL_RECONCILE_ERRORS =>
      val request = context.actorOf(AuditRequest.props(auditLogger, register, channelsDb),"AuditRequest-"+requestCount.toString())
      requestCount+=1
      request forward m

    case m@CHANNEL_GET_INFO =>
      val request = context.actorOf(AuditRequest.props(auditLogger, register, channelsDb),"AuditRequest-"+requestCount.toString())
      requestCount+=1
      request forward m

    case m@CHANNEL_GET_DAILY_STATS =>
      auditLogger forward m
  }

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier can be a shortChannelId (8-byte hex encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(ShortChannelId(channelIdentifier), request))
        .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- register ? fwdReq
    } yield res

  def getBalances(channelId: BinaryData) :Future[AuditReturn] = {
    val channelFut = sendToChannel(channelId.toString(), CMD_GETINFO)
    val auditFut = auditLogger ? GET_BALANCES(channelId)

    val result :Future[AuditReturn] = for {
      channelInfo <- channelFut.recoverWith{case e=> Future {None}}
      auditInfo <- auditFut.mapTo[ChannelBalances]
    } yield {
      (channelInfo,auditInfo) match {
        case (RES_GETINFO(_,_,state: State,data: HasCommitments),cb: ChannelBalances ) =>
          val com=data.commitments
          val lhIn=com.localCommit.spec.htlcs.toSeq.filter(_.direction==IN).map(_.add.amountMsat).sum
          val lhOut=com.localCommit.spec.htlcs.toSeq.filter(_.direction==OUT).map(_.add.amountMsat).sum
          val rhIn=com.remoteCommit.spec.htlcs.toSeq.filter(_.direction==IN).map(_.add.amountMsat).sum
          val rhOut=com.remoteCommit.spec.htlcs.toSeq.filter(_.direction==OUT).map(_.add.amountMsat).sum
          // need the toSeq or duplicate amounts get filtered out!
          AuditReturn( cb,
               CommitmentInfo(com.localCommit.spec.toLocalMsat,com.localCommit.spec.toRemoteMsat, lhIn,lhOut ,com.localNextHtlcId),
               CommitmentInfo(com.remoteCommit.spec.toLocalMsat,com.remoteCommit.spec.toRemoteMsat, rhIn,rhOut, com.remoteNextHtlcId),
               state
              )
        case (None,cb: ChannelBalances) =>
          // used for CLOSED channels that are only in DB now
          channelsDb.getChannel(channelId,true) match {
            case Some(c) =>
              val com=c.commitments
              val lhIn=com.localCommit.spec.htlcs.filter(_.direction==IN).map(_.add.amountMsat).sum
              val lhOut=com.localCommit.spec.htlcs.filter(_.direction==OUT).map(_.add.amountMsat).sum
              val rhIn=com.remoteCommit.spec.htlcs.filter(_.direction==IN).map(_.add.amountMsat).sum
              val rhOut=com.remoteCommit.spec.htlcs.filter(_.direction==OUT).map(_.add.amountMsat).sum

              AuditReturn( cb,
                   CommitmentInfo(0,0,0,0,com.localNextHtlcId),
                   CommitmentInfo(0,0,0,0, com.remoteNextHtlcId),
                   CLOSED
                  )
            case e => throw new ChannelNotFoundException(channelId,"Error - either no entries for channel or AuditLogger is disabled. "+e.toString())
          }

        case a =>
          throw new ChannelNotFoundException(channelId,"Error - either no entries for channel or AuditLogger is disabled. "+a.toString())
      }
    }
    result
  }

}

object AuditBalances {
  def props(auditLogger: ActorRef, register: ActorRef, channelsDb: ChannelsDb) = Props(classOf[AuditBalances], auditLogger, register,channelsDb)
}


sealed trait BALANCE_MESSAGES

case class CHANNEL_BALANCE(channelId: BinaryData) extends BALANCE_MESSAGES
case class CHANNEL_RECONCILE(channelId: BinaryData) extends BALANCE_MESSAGES
case class CHANNEL_RECONCILE_ERRORS() extends BALANCE_MESSAGES
case class CHANNEL_GET_INFO() extends BALANCE_MESSAGES
case class CHANNEL_GET_DAILY_STATS() extends BALANCE_MESSAGES



case class RECONCILE(channelId: BinaryData,state: String, all: Boolean, dbRec: Boolean, dbToCommitRec: Boolean)
case class GET_INFO(channelBalances :ChannelBalances = ChannelBalances(),notNormalChannelBalances :ChannelBalances = ChannelBalances(),
    channelCount: Map[State,Long]= Map[State,Long]().withDefaultValue(0),localCommitiment: CommitmentInfo=CommitmentInfo() )
    
case class DAILY_STATS(date: Long, sentCount: Long, receivedCount: Long, relayedCount: Long, openCount: Long, closedCount: Long, lnFeesMsat: Long, LNprofitMsat: Long, BTCFees: Long)




case class ChannelNotFoundException(val channelId: BinaryData, message: String) extends RuntimeException(message)

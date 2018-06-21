package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef,Props}
import fr.acinq.bitcoin.{BinaryData,MilliSatoshi, Satoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.db._
import fr.acinq.eclair.channel.{State,ChannelIdAssigned}

class AuditLogger(nodeParams: NodeParams, enabled: Boolean) extends Actor with ActorLogging {

  if(enabled) {
    context.system.eventStream.subscribe(self, classOf[PaymentSent])
    context.system.eventStream.subscribe(self, classOf[PaymentRelayed])
    context.system.eventStream.subscribe(self, classOf[PaymentReceived])
    context.system.eventStream.subscribe(self, classOf[PaymentHTLCSent])
    context.system.eventStream.subscribe(self, classOf[PaymentHTLCErrored])
    context.system.eventStream.subscribe(self, classOf[CHANNEL_FUNDING_LOCKED])
    context.system.eventStream.subscribe(self, classOf[CHANNEL_MUTUAL_CLOSE])
    context.system.eventStream.subscribe(self, classOf[CHANNEL_COMMIT_CLOSE])
    context.system.eventStream.subscribe(self, classOf[CHANNEL_CLOSE])
    
    context.system.eventStream.subscribe(self, classOf[CHANNEL_CLOSE_ROUNDING])
    
    context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned]) //We listen for this to get the fee paid on funding Tx.
  }

  var fundingFees = Map[BinaryData,Long]().withDefaultValue[Long](0)

  val db = nodeParams.auditDb

  override def receive: Receive = {

    case s@PaymentSent(_,_,_,_,_,_,_) =>
      db.addAuditEntry(AuditEntry(channel=s.channelId,localHtlcId=s.commitId,htlcMsat=MilliSatoshi(0)-s.amount-s.feesPaid,
          feeMsat=s.feesPaid,entryType=LN_PAYMENT,paymentTxHash=s.paymentHash))

    case r@PaymentReceived(_,_,_,_) =>
      db.addAuditEntry(AuditEntry(channel=r.channelId, localHtlcId= -1L, remoteHtlcId=r.commitId, amountMsat=r.amount,entryType=LN_RECEIPT,paymentTxHash=r.paymentHash))

    case rl@PaymentRelayed(_,_,_,_,_,_,_) =>
      if(!db.checkRelayAdded(rl.channelIdIn,rl.commitIdIn, rl.paymentHash)){
        db.addAuditEntry(AuditEntry(channel=rl.channelIdIn,otherChannel=rl.channelIdOut,localHtlcId = -1L,remoteHtlcId=rl.commitIdIn,
            amountMsat=rl.amountIn,profitInMsat=MilliSatoshi(math.ceil((rl.amountIn-rl.amountOut).amount/2d).toLong),
            entryType=LN_RELAY,paymentTxHash=rl.paymentHash))

        db.addAuditEntry(AuditEntry(channel=rl.channelIdOut,otherChannel=rl.channelIdIn, localHtlcId=rl.commitIdOut,
            htlcMsat=MilliSatoshi(0)-rl.amountOut, profitOutMsat=MilliSatoshi(math.floor((rl.amountIn-rl.amountOut).amount/2d).toLong),
            entryType=LN_RELAY,paymentTxHash=rl.paymentHash))
      }

    case PaymentHTLCSent(amount, paymentHash, channelId, commitId) => 
      if (!db.checkSentAdded(channelId,commitId,paymentHash))
        db.addAuditEntry(AuditEntry(channel=channelId, localHtlcId=commitId,amountMsat=MilliSatoshi(0)-amount,  htlcMsat=amount, entryType=LN_ADD_HTLC, paymentTxHash=paymentHash))

    case PaymentHTLCErrored(channelId, commitId, paymentHash) =>
      db.errorHtlc(channelId,commitId)

    case ChannelIdAssigned(_, _, _, channelId,fundingFee) =>
      fundingFees += ( channelId -> fundingFee)

    case CHANNEL_FUNDING_LOCKED(cid,local,remote) =>
      val fee=fundingFees(cid)
      if (fundingFees.contains(cid)) fundingFees -= cid
      db.addAuditEntry(AuditEntry(channel=cid, amountMsat=local, entryType=OPEN_FUNDING_LOCKED,btcAmount = -local.toLong/1000-fee, btcFee= -fee))

    case CHANNEL_MUTUAL_CLOSE(channelId, amountLocal, amountRemote, txid, btcFee) =>
      val rounding=(amountLocal.amount/1000L).toLong*1000L-amountLocal.amount // put any rounding in roundingMsat Negative means we lost money due to rounding
      val a=AuditEntry(channel=channelId, amountMsat=MilliSatoshi(0) - amountLocal, entryType=MUTUAL_CLOSE,btcAmount = amountLocal.toLong/1000 - btcFee,
          roundingMsat=rounding, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_COMMIT_CLOSE(channelId, amountLocal, amountRemote, txid, btcFee) =>
      val rounding=(amountLocal.amount/1000L).toLong*1000L-amountLocal.amount // put any rounding in roundingMsat Negative means we lost money due to rounding
      val a=AuditEntry(channel=channelId, amountMsat=MilliSatoshi(0) - amountLocal, entryType=COMMIT_CLOSE,delayed = amountLocal.toLong/1000 - btcFee, 
          roundingMsat=rounding, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE(txType @ (CLOSE_DELAYED_MAIN|CLOSE_MAIN|CLOSE_HTLC_DELAYED), channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId, delayed= -btcFee-amount, entryType=txType,btcAmount = amount, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE(txType @ CLOSE_HTLC_SUCCESS, channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId, btcAmount= amount, entryType=txType, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE(txType @ (CLOSE_HTLC_SUCCESS_DELAYED), channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId,delayed= -btcFee-amount, entryType=txType, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE(txType @ CLOSE_HTLC_TIMEOUT, channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId, amountMsat=MilliSatoshi(0),htlcMsat= MilliSatoshi(-(+btcFee+amount)*1000), btcAmount=(amount), entryType=txType, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE(txType @ CLOSE_HTLC_TIMEOUT_DELAYED, channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId, amountMsat=MilliSatoshi(0),htlcMsat= MilliSatoshi(-(+btcFee+amount)*1000), delayed=(amount), entryType=txType, 
          btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)
 
    case CHANNEL_CLOSE(txType @ CLOSE_REVOKED_COMMIT, channelId, amount, txid, btcFee) =>
      val cb=db.channelBalances(channelId)
      val localBalance=cb.amount+cb.htlcAmount
      val rounding=(localBalance.toLong/1000-amount)*1000-(localBalance.amount-amount*1000)
      val a=AuditEntry(channel=channelId, amountMsat=MilliSatoshi(0)-localBalance,penalty=localBalance.amount/1000-amount-btcFee, entryType=txType,btcAmount = amount,
          roundingMsat=rounding, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)
      
    case CHANNEL_CLOSE(txType @ (CLOSE_REVOKED_MAIN|CLOSE_REVOKED_MAIN_PENALTY|CLOSE_REVOKED_HTLC_PENALTY|CLOSE_REVOKED_HTLC_DELAYED_PENALTY|CLOSE_REVOKED_HTLC_REMOTE), channelId, amount, txid, btcFee) =>
      val a=AuditEntry(channel=channelId,penalty= -(amount+btcFee), entryType=txType,btcAmount = amount, btcFee = -btcFee, paymentTxHash=txid)
      if(!db.checkExists(a)) db.addAuditEntry(a)

    case CHANNEL_CLOSE_ROUNDING(channelId) => db.updateRounding(channelId)
      
    case GET_BALANCES(channelId) =>
      sender ! db.channelBalances(channelId)  

    case CHANNEL_GET_DAILY_STATS =>
      sender ! db.dailyData()
  }
}

case class CHANNEL_FUNDING_LOCKED(channelId: BinaryData, amount: MilliSatoshi, pushAmount: MilliSatoshi)
case class CHANNEL_MUTUAL_CLOSE(channelId: BinaryData, amountLocal: MilliSatoshi, amountRemote: MilliSatoshi, txid: BinaryData, btcFee: Long)
case class CHANNEL_COMMIT_CLOSE(channelId: BinaryData, amountLocal: MilliSatoshi, amountRemote: MilliSatoshi, txid: BinaryData, btcFee: Long)
case class CHANNEL_CLOSE(txType: TX_TYPE, channelId: BinaryData, amount: Long, txid: BinaryData, btcFee: Long)
case class CHANNEL_CLOSE_ROUNDING(channelId: BinaryData)

case object LN_PAYMENT extends AuditEntryType
case object LN_RECEIPT extends AuditEntryType
case object LN_RELAY extends AuditEntryType
case object LN_ADD_HTLC extends AuditEntryType
case object LN_ERROR_HTLC extends AuditEntryType

case object OPEN_FUNDING_LOCKED extends AuditEntryType
case object MUTUAL_CLOSE extends AuditEntryType
case object COMMIT_CLOSE extends AuditEntryType
case object ROUNDING_CLOSE extends AuditEntryType

sealed trait TX_TYPE extends AuditEntryType
case object CLOSE_DELAYED_MAIN extends TX_TYPE 
case object CLOSE_MAIN extends TX_TYPE 
case object CLOSE_HTLC_SUCCESS extends TX_TYPE
case object CLOSE_HTLC_SUCCESS_DELAYED extends TX_TYPE
case object CLOSE_HTLC_TIMEOUT extends TX_TYPE
case object CLOSE_HTLC_TIMEOUT_DELAYED extends TX_TYPE
case object CLOSE_HTLC_DELAYED extends TX_TYPE
case object CLOSE_REVOKED_COMMIT extends TX_TYPE with AuditEntryType
case object CLOSE_REVOKED_MAIN extends TX_TYPE with AuditEntryType
case object CLOSE_REVOKED_MAIN_PENALTY extends TX_TYPE with AuditEntryType
case object CLOSE_REVOKED_HTLC_PENALTY extends TX_TYPE with AuditEntryType
case object CLOSE_REVOKED_HTLC_REMOTE extends TX_TYPE with AuditEntryType
case object CLOSE_REVOKED_HTLC_DELAYED_PENALTY extends TX_TYPE with AuditEntryType
case object CLOSE_ERROR_FUTURE_PUBLISHED extends TX_TYPE with AuditEntryType

case class GET_BALANCES(channelId: BinaryData)

case class CommitmentInfo(toLocalmSat: Long=0,toRemotemSat: Long=0, inflightInHtlc: Long=0, inflightOutHtlc: Long=0 ,nextHtlcId: Long=0) {
  import Math.max
  def +(that: CommitmentInfo)=
    new CommitmentInfo(this.toLocalmSat+that.toLocalmSat,this.toRemotemSat+that.toRemotemSat,this.inflightInHtlc+that.inflightInHtlc
        ,this.inflightOutHtlc+that.inflightOutHtlc,max(this.nextHtlcId,that.nextHtlcId))
  
}



case class AuditReturn(channelBalances: ChannelBalances, localCommitiment: CommitmentInfo, remoteCommitment: CommitmentInfo, state:State)

object AuditLogger {
  def props(nodeParams: NodeParams, enabled: Boolean) = Props(classOf[AuditLogger], nodeParams, enabled)
}

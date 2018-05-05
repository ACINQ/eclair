package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.payment.DAILY_STATS
/**
  * Store audit of funds that pass through this node. Should enable debugging of any fund loss related issues.
  * 
  */
trait AuditDb {
  def addAuditEntry(a: AuditEntry)
  def channelBalances(channelId: BinaryData): ChannelBalances
  def checkChannelClosed(channelId: BinaryData, txid: BinaryData): Boolean
  def checkRelayAdded(channelId: BinaryData, htlcId: Long, paymentHash: BinaryData): Boolean
  def errorHtlc(channelId: BinaryData, htlcId: Long): Unit
  def checkExists(channelId: BinaryData ,paymentTxHash: BinaryData): Boolean
  def checkExists(a: AuditEntry): Boolean
  def checkSentAdded(channelId: BinaryData, htlcId: Long, paymentHash: BinaryData): Boolean
  def dailyData(): Seq[DAILY_STATS]
  def updateRounding(channelId:BinaryData)
}
trait AuditEntryType

case class AuditEntry(channel: BinaryData, otherChannel: BinaryData=BinaryData.empty, localHtlcId: Long = -1L, remoteHtlcId: Long = -1L, amountMsat: MilliSatoshi =MilliSatoshi(0), htlcMsat: MilliSatoshi =MilliSatoshi(0),
    profitInMsat: MilliSatoshi =MilliSatoshi(0), profitOutMsat: MilliSatoshi=MilliSatoshi(0), feeMsat: MilliSatoshi=MilliSatoshi(0), entryType: AuditEntryType, 
    paymentTxHash: BinaryData=BinaryData.empty, btcAmount: Long = 0L, btcFee: Long =0L, delayed: Long = 0L,  penalty: Long = 0L, roundingMsat: Long=0)

//select quote(channelid),max(localhtlcid),max(remotehtlcid),sum(amountmsat),sum(profitmsat),sum(feemsat) from audit group by channelid;

case class ChannelBalances(maxLocalHTLCId: Long= -1, maxRemoteHTLCId: Long= -1, amount:MilliSatoshi=MilliSatoshi(0), htlcAmount:MilliSatoshi=MilliSatoshi(0),
    profitIn: MilliSatoshi=MilliSatoshi(0), profitOut: MilliSatoshi=MilliSatoshi(0), fees: MilliSatoshi=MilliSatoshi(0), 
    btcAmount: Long=0, btcFee: Long=0, penalty: Long=0, delayed: Long=0, sentMsat:MilliSatoshi=MilliSatoshi(0), receivedMsat:MilliSatoshi = MilliSatoshi(0),
    rounding:MilliSatoshi=MilliSatoshi(0),relaySentMsat:MilliSatoshi=MilliSatoshi(0), relayReceivedMsat:MilliSatoshi = MilliSatoshi(0)){
  override def toString={
    "(maxLocalHTLCId:" + maxLocalHTLCId + ", maxRemoteHTLCId:"+maxRemoteHTLCId+", amountMsat:"+amount.amount+", htlcAmountMsat:"+htlcAmount.amount+", profitInMsat:"+profitIn.amount+", profitOutMsat:"+profitOut.amount + 
    ", feesMsat:" + fees.amount + ", btcAmount:"+btcAmount+", btcFee:"+btcFee+", penalty:"+penalty+", delayed:"+delayed+", sentMsat:"+sentMsat+", receivedMsat:"+receivedMsat+", roundingMsat:"+rounding+
    ", relaySentMsat:"+relaySentMsat+", relayReceivedMsat:"+relayReceivedMsat+")"
  }
    import Math.max
  def +(that: ChannelBalances)=
    new ChannelBalances(max(this.maxLocalHTLCId,that.maxLocalHTLCId),max(this.maxRemoteHTLCId,that.maxRemoteHTLCId),this.amount + that.amount, this.htlcAmount+that.htlcAmount,this.profitIn+that.profitIn
        ,this.profitOut+that.profitOut,this.fees+that.fees, this.btcAmount+that.btcAmount, this.btcFee+that.btcFee, this.penalty+that.penalty, this.delayed+that.delayed, this.sentMsat+that.sentMsat
        ,this.receivedMsat+that.receivedMsat, this.rounding+that.rounding,this.relaySentMsat+that.relaySentMsat,this.relayReceivedMsat+that.relayReceivedMsat)
  
}


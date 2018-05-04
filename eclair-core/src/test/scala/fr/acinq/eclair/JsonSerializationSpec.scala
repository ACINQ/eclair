package fr.acinq.eclair

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.db.ChannelStateSpec
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class JsonSerializationSpec extends FunSuite {

  import upickle.default._

  implicit val txReadWrite: ReadWriter[Transaction] = readwriter[String].bimap[Transaction](_.toString(), Transaction.read(_))
  implicit val outpointReadWrite: ReadWriter[OutPoint] = readwriter[String].bimap[OutPoint](op => s"${op.hash}:${op.index}", s => ???)
  implicit val publicKeyReadWriter: ReadWriter[PublicKey] = readwriter[String].bimap[PublicKey](_.toString(), s => PublicKey(BinaryData(s)))
  implicit val pointReadWriter: ReadWriter[Point] = readwriter[String].bimap[Point](_.toString(), s => Point(BinaryData(s)))
  implicit val keyPathReadWriter: ReadWriter[DeterministicWallet.KeyPath] = readwriter[String].bimap[DeterministicWallet.KeyPath](_.toString(), _ => DeterministicWallet.KeyPath(0L :: Nil))
  implicit val binarydataReadWriter: ReadWriter[BinaryData] = readwriter[String].bimap[BinaryData](_.toString(), s => BinaryData(s))
  implicit val uint64ReadWriter: ReadWriter[UInt64] = readwriter[String].bimap[UInt64](_.toString, s => UInt64(s.toLong))
  implicit val localParamsReadWriter: ReadWriter[LocalParams] = macroRW
  implicit val remoteParamsReadWriter: ReadWriter[RemoteParams] = macroRW
  implicit val directionReadWriter: ReadWriter[Direction] = readwriter[String].bimap[Direction](_.toString, _ match {
    case "IN" => IN
    case "OUT" => OUT
  })
  implicit val updateAddHtlcReadWriter: ReadWriter[UpdateAddHtlc] = macroRW
  implicit val updateFailHtlcReadWriter: ReadWriter[UpdateFailHtlc] = macroRW
  implicit val updateMessageReadWriter: ReadWriter[UpdateMessage] = ReadWriter.merge(macroRW[UpdateAddHtlc], macroRW[UpdateFailHtlc])
  implicit val directeddHtlcReadWriter: ReadWriter[DirectedHtlc] = macroRW
  implicit val commitmentSpecReadWriter: ReadWriter[CommitmentSpec] = macroRW
  implicit val localChangesReadWriter: ReadWriter[LocalChanges] = macroRW
  implicit val satoshiReadWriter: ReadWriter[Satoshi] = macroRW
  implicit val txOutReadWriter: ReadWriter[TxOut] = macroRW
  implicit val inputInfoReadWriter: ReadWriter[InputInfo] = macroRW
  implicit val transactionWithInputInfoReadWriter: ReadWriter[TransactionWithInputInfo] = ReadWriter.merge(
    macroRW[CommitTx], macroRW[HtlcSuccessTx], macroRW[HtlcTimeoutTx], macroRW[ClaimHtlcSuccessTx], macroRW[ClaimHtlcTimeoutTx],
    macroRW[ClaimP2WPKHOutputTx], macroRW[ClaimDelayedOutputTx], macroRW[ClaimDelayedOutputPenaltyTx], macroRW[MainPenaltyTx], macroRW[HtlcPenaltyTx], macroRW[ClosingTx])
  implicit val hlcTxAndSigsReadWriter: ReadWriter[HtlcTxAndSigs] = macroRW
  implicit val commitTxReadWriter: ReadWriter[CommitTx] = macroRW
  implicit val publishableTxsReadWriter: ReadWriter[PublishableTxs] = macroRW
  implicit val localCommitReadWriter: ReadWriter[LocalCommit] = macroRW
  implicit val remoteCommitsReadWriter: ReadWriter[RemoteCommit] = macroRW
  implicit val commitSgReadWriter: ReadWriter[CommitSig] = macroRW
  implicit val waitingForRevocationReadWriter: ReadWriter[WaitingForRevocation] = macroRW
  implicit val paymentOriginReadWriter: ReadWriter[Origin] = readwriter[String].bimap[Origin](_.toString, _ => fr.acinq.eclair.payment.Local(None))
  implicit val remoteChangesReadWriter: ReadWriter[RemoteChanges] = macroRW
  implicit val shaChainReadWriter: ReadWriter[ShaChain] = macroRW
  implicit val commitmentsReadWriter: ReadWriter[Commitments] = macroRW
  implicit val actorRefReadWriter: ReadWriter[ActorRef] = readwriter[String].bimap[ActorRef](_.toString, _ => ActorRef.noSender)
  implicit val shortChannelIdReadWriter: ReadWriter[ShortChannelId] = readwriter[String].bimap[ShortChannelId](_.toString, s => ShortChannelId(s))

  implicit val initReadWriter: ReadWriter[Init] = macroRW
  implicit val openChannelReadWriter: ReadWriter[OpenChannel] = macroRW
  implicit val acceptChannelReadWriter: ReadWriter[AcceptChannel] = macroRW
  implicit val fundingCreatedReadWriter: ReadWriter[FundingCreated] = macroRW
  implicit val fundingLockedReadWriter: ReadWriter[FundingLocked] = macroRW
  implicit val fundingSignedReadWriter: ReadWriter[FundingSigned] = macroRW
  implicit val channelAnnouncementReadWriter: ReadWriter[ChannelAnnouncement] = macroRW
  implicit val channelUpdateReadWriter: ReadWriter[ChannelUpdate] = macroRW
  implicit val shutdownReadWriter: ReadWriter[Shutdown] = macroRW

  implicit val inputInitFunderReadWriter: ReadWriter[INPUT_INIT_FUNDER] = macroRW
  implicit val inputInitFundeeReadWriter: ReadWriter[INPUT_INIT_FUNDEE] = macroRW

  implicit val dataWaitForAcceptChannelReadWriter: ReadWriter[DATA_WAIT_FOR_ACCEPT_CHANNEL] = macroRW
  implicit val dataWaitForOpenChannelReadWriter: ReadWriter[DATA_WAIT_FOR_OPEN_CHANNEL] = macroRW
  implicit val dataWaitForFundingCreatedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_CREATED] = macroRW
  implicit val dataWaitForFundingInternalReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_INTERNAL] = macroRW
  implicit val dataWaitForFundingSignedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_SIGNED] = macroRW
  implicit val dataWaitForFundingConfirmedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_CONFIRMED] = macroRW
  implicit val dataWaitForFundingLockedReadWriter: ReadWriter[DATA_WAIT_FOR_FUNDING_LOCKED] = macroRW
  implicit val dataNormalReadWriter: ReadWriter[DATA_NORMAL] = macroRW


  // DATA_WAIT_FOR_FUNDING_LOCKED

  // DATA_WAIT_FOR_ACCEPT_CHANNEL

  /*
    case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: BinaryData) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

   */

  test("serialize LocalParams") {
    val localParams = LocalParams(
      nodeId = randomKey.publicKey,
      channelKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      isFunder = Random.nextBoolean(),
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))

    println(write(localParams))

  }

  test("serialize RemoteParams") {
    val remoteParams = RemoteParams(
      nodeId = randomKey.publicKey,
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey.publicKey,
      revocationBasepoint = randomKey.publicKey.value,
      paymentBasepoint = randomKey.publicKey.value,
      delayedPaymentBasepoint = randomKey.publicKey.value,
      htlcBasepoint = randomKey.publicKey.value,
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))

    println(write(remoteParams))
  }

  test("serialize CommitmentSpec") {
    val spec = CommitmentSpec(Set(DirectedHtlc(IN, UpdateAddHtlc(randomKey.publicKey.value.toBin(true), 421, 1245, randomBytes(32), 1000, BinaryData("010101")))), feeratePerKw = 1233, toLocalMsat = 100, toRemoteMsat = 200)
    println(write(spec))
  }

  test("serialize LocalChanges") {
    val channelId = randomKey.publicKey.value.toBin(true)
    val add = UpdateAddHtlc(channelId, 421, 1245, randomBytes(32), 1000, BinaryData("010101"))
    val fail = UpdateFailHtlc(channelId, 42, BinaryData("0101"))
    val localChanges = LocalChanges(proposed = add :: add :: fail :: Nil, signed = add :: Nil, acked = fail :: fail :: Nil)
    println(write(localChanges))
  }

  test("serialize Commitments") {
    val commitments = ChannelStateSpec.commitments
    println(write(commitments))
  }

  test("serialize DATA_NORMAL") {
    val data = ChannelStateSpec.normal
    println(write(data))
  }
}

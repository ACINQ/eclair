/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.InteractiveTx._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

class InteractiveTxSpec extends AnyFunSuiteLike {

  import InteractiveTxSpec._

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  test("initiator only") {
    //     +-------+                       +-------+
    //     |       |--(1)- tx_add_input -->|       |
    //     |       |<-(2)- tx_complete ----|       |
    //     |       |--(3)- tx_add_input -->|       |
    //     |   A   |<-(4)- tx_complete ----|   B   |
    //     |       |--(5)- tx_add_output ->|       |
    //     |       |<-(6)- tx_complete ----|       |
    //     |       |--(7)- tx_complete --->|       |
    //     +-------+                       +-------+

    val channelId = randomBytes32()
    val fundingScript = createFundingScript()
    val initiatorParams = InteractiveTxParams(channelId, isInitiator = true, 100_000 sat, 0 sat, fundingScript, 0, 660 sat, FeeratePerKw(2500 sat))
    val initiatorContributions = FundingContributions(
      Seq(createInput(channelId, UInt64(0), 60_000 sat), createInput(channelId, UInt64(2), 45_000 sat)),
      Seq(TxAddOutput(channelId, UInt64(0), initiatorParams.fundingAmount, initiatorParams.fundingPubkeyScript)),
    )
    val nonInitiatorParams = InteractiveTxParams(channelId, isInitiator = false, 0 sat, 100_000 sat, fundingScript, 0, 660 sat, FeeratePerKw(2500 sat))
    val nonInitiatorContributions = FundingContributions(Nil, Nil)

    // A --- tx_add_input --> B
    val (initiatorSession1, Some(msg1)) = InteractiveTx.start(initiatorParams, initiatorContributions)
    assert(msg1 === initiatorContributions.inputs.head)
    // A <--- tx_complete --- B
    val (nonInitiatorSession1, None) = InteractiveTx.start(nonInitiatorParams, nonInitiatorContributions)
    val Right((nonInitiatorSession2, Some(msg2))) = InteractiveTx.receive(nonInitiatorSession1, nonInitiatorParams, msg1)
    assert(msg2 === TxComplete(channelId))
    // A --- tx_add_input --> B
    val Right((initiatorSession2, Some(msg3))) = InteractiveTx.receive(initiatorSession1, initiatorParams, msg2)
    assert(msg3 === initiatorContributions.inputs.last)
    // A <--- tx_complete --- B
    val Right((nonInitiatorSession3, Some(msg4))) = InteractiveTx.receive(nonInitiatorSession2, nonInitiatorParams, msg3)
    assert(msg4 === TxComplete(channelId))
    // A --- tx_add_output --> B
    val Right((initiatorSession3, Some(msg5))) = InteractiveTx.receive(initiatorSession2, initiatorParams, msg4)
    assert(msg5 === initiatorContributions.outputs.head)
    assert(!initiatorSession3.isComplete)
    // A <--- tx_complete --- B
    val Right((nonInitiatorSession4, Some(msg6))) = InteractiveTx.receive(nonInitiatorSession3, nonInitiatorParams, msg5)
    assert(msg6 === TxComplete(channelId))
    assert(!nonInitiatorSession4.isComplete)
    // A --- tx_complete ---> B
    val Right((initiatorSession4, Some(msg7))) = InteractiveTx.receive(initiatorSession3, initiatorParams, msg6)
    assert(msg7 === TxComplete(channelId))
    assert(initiatorSession4.isComplete)
    val Right((nonInitiatorSession5, None)) = InteractiveTx.receive(nonInitiatorSession4, nonInitiatorParams, msg7)
    assert(nonInitiatorSession5.isComplete)

    val Right((initiatorTx, initiatorIndex)) = InteractiveTx.validateTx(initiatorSession4, initiatorParams)
    val Right((nonInitiatorTx, nonInitiatorIndex)) = InteractiveTx.validateTx(nonInitiatorSession5, nonInitiatorParams)
    assert(initiatorIndex === nonInitiatorIndex)
    assert(initiatorIndex === 0)
    assert(initiatorTx.buildUnsignedTx() === nonInitiatorTx.buildUnsignedTx())
    val tx = initiatorTx.buildUnsignedTx()
    assert(tx.txIn.length === 2)
    assert(tx.txOut.length === 1)
    assert(tx.txOut.head === TxOut(initiatorParams.fundingAmount, fundingScript))
  }

  test("initiator and non-initiator") {
    //     +-------+                       +-------+
    //     |       |--(1)- tx_add_input -->|       |
    //     |       |<-(2)- tx_add_input ---|       |
    //     |       |--(3)- tx_add_input -->|       |
    //     |   A   |<-(4)- tx_add_output --|   B   |
    //     |       |--(5)- tx_add_output ->|       |
    //     |       |<-(6)- tx_complete ----|       |
    //     |       |--(7)- tx_add_output ->|       |
    //     |       |<-(8)- tx_complete ----|       |
    //     |       |--(9)- tx_complete --->|       |
    //     +-------+                       +-------+

    val channelId = randomBytes32()
    val fundingScript = createFundingScript()
    val initiatorParams = InteractiveTxParams(channelId, isInitiator = true, 100_000 sat, 50_000 sat, fundingScript, 0, 660 sat, FeeratePerKw(2500 sat))
    val initiatorContributions = FundingContributions(
      Seq(createInput(channelId, UInt64(0), 60_000 sat), createInput(channelId, UInt64(2), 45_000 sat)),
      Seq(TxAddOutput(channelId, UInt64(0), initiatorParams.fundingAmount, initiatorParams.fundingPubkeyScript), TxAddOutput(channelId, UInt64(2), 2500 sat, createChangeScript())),
    )
    val nonInitiatorParams = InteractiveTxParams(channelId, isInitiator = false, 50_000 sat, 100_000 sat, fundingScript, 0, 660 sat, FeeratePerKw(2_500 sat))
    val nonInitiatorContributions = FundingContributions(
      Seq(createInput(channelId, UInt64(1), 58_000 sat)),
      Seq(TxAddOutput(channelId, UInt64(1), 7_000 sat, createChangeScript()))
    )

    // A --- tx_add_input --> B
    val (initiatorSession1, Some(msg1)) = InteractiveTx.start(initiatorParams, initiatorContributions)
    assert(msg1 === initiatorContributions.inputs.head)
    // A <-- tx_add_input --- B
    val (nonInitiatorSession1, None) = InteractiveTx.start(nonInitiatorParams, nonInitiatorContributions)
    val Right((nonInitiatorSession2, Some(msg2))) = InteractiveTx.receive(nonInitiatorSession1, nonInitiatorParams, msg1)
    assert(msg2 === nonInitiatorContributions.inputs.head)
    // A --- tx_add_input --> B
    val Right((initiatorSession2, Some(msg3))) = InteractiveTx.receive(initiatorSession1, initiatorParams, msg2)
    assert(msg3 === initiatorContributions.inputs.last)
    // A <-- tx_add_output --- B
    val Right((nonInitiatorSession3, Some(msg4))) = InteractiveTx.receive(nonInitiatorSession2, nonInitiatorParams, msg3)
    assert(msg4 === nonInitiatorContributions.outputs.head)
    // A --- tx_add_output --> B
    val Right((initiatorSession3, Some(msg5))) = InteractiveTx.receive(initiatorSession2, initiatorParams, msg4)
    assert(msg5 === initiatorContributions.outputs.head)
    // A <-- tx_complete --- B
    val Right((nonInitiatorSession4, Some(msg6))) = InteractiveTx.receive(nonInitiatorSession3, nonInitiatorParams, msg5)
    assert(msg6 === TxComplete(channelId))
    // A --- tx_add_output --> B
    val Right((initiatorSession4, Some(msg7))) = InteractiveTx.receive(initiatorSession3, initiatorParams, msg6)
    assert(msg7 === initiatorContributions.outputs.last)
    assert(!initiatorSession4.isComplete)
    // A <-- tx_complete --- B
    val Right((nonInitiatorSession5, Some(msg8))) = InteractiveTx.receive(nonInitiatorSession4, nonInitiatorParams, msg7)
    assert(msg8 === TxComplete(channelId))
    assert(!nonInitiatorSession5.isComplete)
    // A --- tx_complete --> B
    val Right((initiatorSession5, Some(msg9))) = InteractiveTx.receive(initiatorSession4, initiatorParams, msg8)
    assert(initiatorSession5.isComplete)
    assert(msg9 === TxComplete(channelId))
    val Right((nonInitiatorSession6, None)) = InteractiveTx.receive(nonInitiatorSession5, nonInitiatorParams, msg9)
    assert(nonInitiatorSession6.isComplete)

    val Right((initiatorTx, initiatorIndex)) = InteractiveTx.validateTx(initiatorSession5, initiatorParams)
    val Right((nonInitiatorTx, nonInitiatorIndex)) = InteractiveTx.validateTx(nonInitiatorSession6, nonInitiatorParams)
    assert(initiatorIndex === nonInitiatorIndex)
    assert(initiatorIndex === 0)
    assert(initiatorTx.buildUnsignedTx() === nonInitiatorTx.buildUnsignedTx())
    val tx = initiatorTx.buildUnsignedTx()
    assert(tx.txIn.length === 3)
    assert(tx.txOut.length === 3)
    assert(tx.txOut.head === TxOut(initiatorParams.fundingAmount, fundingScript))
  }

  test("invalid input") {
    val params = InteractiveTxParams(randomBytes32(), isInitiator = true, 0 sat, 100_000 sat, createFundingScript(), 0, 660 sat, FeeratePerKw(2_500 sat))
    val mixedOutputs = Seq(
      TxOut(2500 sat, Script.pay2wpkh(randomKey().publicKey)),
      TxOut(2500 sat, Script.pay2pkh(randomKey().publicKey)),
    )
    val mixedTx = Transaction(2, Nil, mixedOutputs, 0)
    val session = {
      val (session1, Some(msg)) = InteractiveTx.start(params, FundingContributions(Nil, Nil))
      assert(msg === TxComplete(params.channelId))
      assert(!session1.isComplete)
      val Right((session2, _)) = InteractiveTx.receive(session1, params, TxAddInput(params.channelId, UInt64(7), mixedTx, 0, 0))
      session2
    }
    assert(InteractiveTx.receive(session, params, createInput(params.channelId, UInt64(0), 15_000 sat)) === Left(InvalidSerialId(params.channelId, UInt64(0))))
    assert(InteractiveTx.receive(session, params, TxAddInput(params.channelId, UInt64(1), mixedTx, 2, 0)) === Left(InputOutOfBounds(params.channelId, UInt64(1), mixedTx.txid, 2)))
    assert(InteractiveTx.receive(session, params, createInput(params.channelId, UInt64(7), 15_000 sat)) === Left(DuplicateSerialId(params.channelId, UInt64(7))))
    assert(InteractiveTx.receive(session, params, TxAddInput(params.channelId, UInt64(13), mixedTx, 0, 0)) === Left(DuplicateInput(params.channelId, UInt64(13), mixedTx.txid, 0)))
    assert(InteractiveTx.receive(session, params, TxAddInput(params.channelId, UInt64(17), mixedTx, 1, 0)) === Left(NonSegwitInput(params.channelId, UInt64(17), mixedTx.txid, 1)))
  }

  test("invalid output") {
    val params = InteractiveTxParams(randomBytes32(), isInitiator = false, 0 sat, 100_000 sat, createFundingScript(), 0, 660 sat, FeeratePerKw(2_500 sat))
    val session = {
      val (session1, None) = InteractiveTx.start(params, FundingContributions(Nil, Nil))
      assert(!session1.isComplete)
      val Right((session2, _)) = InteractiveTx.receive(session1, params, TxAddOutput(params.channelId, UInt64(4), 45_000 sat, createChangeScript()))
      session2
    }
    assert(InteractiveTx.receive(session, params, TxAddOutput(params.channelId, UInt64(3), 15_000 sat, createChangeScript())) === Left(InvalidSerialId(params.channelId, UInt64(3))))
    assert(InteractiveTx.receive(session, params, TxAddOutput(params.channelId, UInt64(4), 15_000 sat, createChangeScript())) === Left(DuplicateSerialId(params.channelId, UInt64(4))))
    assert(InteractiveTx.receive(session, params, TxAddOutput(params.channelId, UInt64(6), 659 sat, createChangeScript())) === Left(OutputBelowDust(params.channelId, UInt64(6), 659 sat, 660 sat)))
    assert(InteractiveTx.receive(session, params, TxAddOutput(params.channelId, UInt64(8), 15_000 sat, Script.write(Script.pay2pkh(randomKey().publicKey)))) === Left(NonSegwitOutput(params.channelId, UInt64(8))))
  }

  test("too many protocol rounds") {
    val params = InteractiveTxParams(randomBytes32(), isInitiator = false, 0 sat, 100_000 sat, createFundingScript(), 0, 660 sat, FeeratePerKw(2_500 sat))
    var session = InteractiveTx.start(params, FundingContributions(Nil, Nil))._1
    (1 until InteractiveTx.MAX_INPUTS_OUTPUTS_RECEIVED).foreach(i => {
      val Right((nextSession, _)) = InteractiveTx.receive(session, params, createInput(params.channelId, UInt64(2 * i), 2500 sat))
      session = nextSession
    })
    val Left(f) = InteractiveTx.receive(session, params, createInput(params.channelId, UInt64(15000), 1561 sat))
    assert(f === TooManyInteractiveTxRounds(params.channelId))
  }

  test("remove input/output") {
    val params = InteractiveTxParams(randomBytes32(), isInitiator = false, 0 sat, 100_000 sat, createFundingScript(), 0, 660 sat, FeeratePerKw(2_500 sat))
    var session = InteractiveTx.start(params, FundingContributions(Nil, Nil))._1
    // A --- tx_add_input --> B
    val input = createInput(params.channelId, UInt64(0), 150_000 sat)
    session = InteractiveTx.receive(session, params, input).toOption.get._1
    // A --- tx_add_input --> B
    session = InteractiveTx.receive(session, params, createInput(params.channelId, UInt64(2), 10_000 sat)).toOption.get._1
    // A --- tx_add_output --> B
    session = InteractiveTx.receive(session, params, TxAddOutput(params.channelId, UInt64(0), 25_000 sat, createChangeScript())).toOption.get._1
    // A --- tx_add_output --> B
    val output = TxAddOutput(params.channelId, UInt64(4), params.fundingAmount, params.fundingPubkeyScript)
    session = InteractiveTx.receive(session, params, output).toOption.get._1
    assert(InteractiveTx.receive(session, params, TxRemoveInput(params.channelId, UInt64(4))) === Left(UnknownSerialId(params.channelId, UInt64(4))))
    assert(InteractiveTx.receive(session, params, TxRemoveOutput(params.channelId, UInt64(2))) === Left(UnknownSerialId(params.channelId, UInt64(2))))
    // A --- tx_remove_input --> B
    session = InteractiveTx.receive(session, params, TxRemoveInput(params.channelId, UInt64(2))).toOption.get._1
    // A --- tx_remove_output --> B
    session = InteractiveTx.receive(session, params, TxRemoveOutput(params.channelId, UInt64(0))).toOption.get._1
    // A --- tx_complete --> B
    session = InteractiveTx.receive(session, params, TxComplete(params.channelId)).toOption.get._1
    assert(session.isComplete)
    val tx = InteractiveTx.validateTx(session, params).toOption.get._1.buildUnsignedTx()
    assert(tx.txIn.length === 1)
    assert(tx.txOut.length === 1)
    assert(tx.txIn.head.outPoint === toOutPoint(input))
    assert(tx.txOut.head === TxOut(output.amount, output.pubkeyScript))
  }

  test("validate transaction") {
    val params = InteractiveTxParams(randomBytes32(), isInitiator = true, 100_000 sat, 50_000 sat, createFundingScript(), 0, 660 sat, FeeratePerKw(5000 sat))
    val validSession = InteractiveTxSession(
      toSend = Nil,
      localInputs = Seq(createInput(params.channelId, UInt64(0), 150_000 sat)),
      remoteInputs = Seq(createInput(params.channelId, UInt64(1), 75_000 sat)),
      localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount, params.fundingPubkeyScript), TxAddOutput(params.channelId, UInt64(2), 40_000 sat, createChangeScript())),
      remoteOutputs = Seq(TxAddOutput(params.channelId, UInt64(1), 20_000 sat, createChangeScript())),
      txCompleteSent = true,
      txCompleteReceived = true,
    )
    assert(validSession.isComplete)
    assert(InteractiveTx.validateTx(validSession, params).isRight)

    val incompleteSession = validSession.copy(txCompleteSent = false)
    assert(InteractiveTx.validateTx(incompleteSession, params) === Left(InvalidCompleteInteractiveTx(params.channelId)))

    val invalidSessions = Seq(
      // Too many inputs.
      validSession.copy(
        localInputs = (1 to 53).map(i => createInput(params.channelId, UInt64(2 * i), 2000 sat)),
        remoteInputs = (1 to 200).map(i => createInput(params.channelId, UInt64(2 * i + 1), 1000 sat)),
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount, params.fundingPubkeyScript)),
        remoteOutputs = Seq(TxAddOutput(params.channelId, UInt64(1), 140_000 sat, createChangeScript())),
      ),
      // Too many outputs.
      validSession.copy(
        localInputs = Seq(createInput(params.channelId, UInt64(0), 210_000 sat)),
        remoteInputs = Seq(createInput(params.channelId, UInt64(1), 210_000 sat)),
        localOutputs = TxAddOutput(params.channelId, UInt64(0), params.fundingAmount, params.fundingPubkeyScript) +: (1 to 52).map(i => TxAddOutput(params.channelId, UInt64(2 * i), 1000 sat, createChangeScript())),
        remoteOutputs = (1 to 200).map(i => TxAddOutput(params.channelId, UInt64(2 * i + 1), 1000 sat, createChangeScript())),
      ),
      // Funding output is missing.
      validSession.copy(
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(2), 140_000 sat, createChangeScript()))
      ),
      // Multiple funding outputs.
      validSession.copy(
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount, params.fundingPubkeyScript), TxAddOutput(params.channelId, UInt64(2), 40_000 sat, params.fundingPubkeyScript))
      ),
      // Invalid funding amount.
      validSession.copy(
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount - 1.sat, params.fundingPubkeyScript), TxAddOutput(params.channelId, UInt64(2), 40_000 sat, createChangeScript())),
      ),
      validSession.copy(
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount + 1.sat, params.fundingPubkeyScript), TxAddOutput(params.channelId, UInt64(2), 40_000 sat, createChangeScript())),
      ),
      // Local amount insufficient.
      validSession.copy(
        localOutputs = Seq(TxAddOutput(params.channelId, UInt64(0), params.fundingAmount, params.fundingPubkeyScript), TxAddOutput(params.channelId, UInt64(2), 60_000 sat, createChangeScript())),
      ),
      // Remote amount insufficient.
      validSession.copy(
        remoteOutputs = Seq(TxAddOutput(params.channelId, UInt64(1), 30_000 sat, createChangeScript())),
      ),
      // Feerate too low.
      validSession.copy(
        localInputs = Seq(createInput(params.channelId, UInt64(0), 140_001 sat)),
        remoteInputs = Seq(createInput(params.channelId, UInt64(1), 70_001 sat)),
      ),
    )
    for (session <- invalidSessions) {
      assert(session.isComplete)
      assert(InteractiveTx.validateTx(session, params) === Left(InvalidCompleteInteractiveTx(params.channelId)))
    }
  }

}

object InteractiveTxSpec {

  def createFundingScript(): ByteVector = {
    Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
  }

  def createChangeScript(): ByteVector = {
    Script.write(Script.pay2wpkh(randomKey().publicKey))
  }

  def createInput(channelId: ByteVector32, serialId: UInt64, amount: Satoshi): TxAddInput = {
    val previousTx = Transaction(2, Nil, Seq(createTxOut(amount), createTxOut(amount), createTxOut(amount)), 0)
    TxAddInput(channelId, serialId, previousTx, 1, 0)
  }

  def createTxOut(amount: Satoshi): TxOut = {
    TxOut(amount, createChangeScript())
  }

  def computeFees(sharedTx: SharedTransaction): Satoshi = {
    computeFees(sharedTx.localInputs ++ sharedTx.remoteInputs, sharedTx.localOutputs ++ sharedTx.remoteOutputs)
  }

  def computeFees(inputs: Seq[TxAddInput], outputs: Seq[TxAddOutput]): Satoshi = {
    val amountIn = inputs.map(i => i.previousTx.txOut(i.previousTxOutput.toInt).amount).sum
    val amountOut = outputs.map(_.amount).sum
    amountIn - amountOut
  }

}

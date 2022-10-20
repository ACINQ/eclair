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

package fr.acinq.eclair.plugins.peerswap

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Scripts.der
import scodec.bits.ByteVector

/**
 * Created by remyers on 06/05/2022
 */
object SwapScripts {
  val claimByCsvDelta: CltvExpiryDelta = CltvExpiryDelta(1008)

  /**
   * The opening transaction output script is a P2WSH:
   */
  def swapOpening(makerPubkey: PublicKey, takerPubkey: PublicKey, paymentHash: ByteVector, csvDelay: CltvExpiryDelta = claimByCsvDelta): Seq[ScriptElt] = {
    // @formatter:off
    // To you with revocation key
    OP_PUSHDATA(makerPubkey) :: OP_CHECKSIG :: OP_NOTIF ::
      OP_PUSHDATA(makerPubkey) :: OP_CHECKSIG :: OP_NOTIF ::
      OP_SIZE :: Scripts.encodeNumber(32) :: OP_EQUALVERIFY :: OP_SHA256 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_ENDIF ::
      OP_PUSHDATA(takerPubkey) :: OP_CHECKSIG ::
      OP_ELSE ::
      Scripts.encodeNumber(csvDelay.toInt) :: OP_CHECKSEQUENCEVERIFY ::
      OP_ENDIF :: Nil
    // @formatter:on
  }

  /**
   * This is the desired way to finish a swap. The taker sends the funds to its address by revealing the preimage of the swap invoice.
   * witness: <signature_for_taker> <preimage> <> <> <redeem_script>
   */
  def witnessClaimByInvoice(takerSig: ByteVector64, paymentPreimage: ByteVector32, redeemScript: ByteVector): ScriptWitness =
    ScriptWitness(der(takerSig) :: paymentPreimage.bytes :: ByteVector.empty :: ByteVector.empty :: redeemScript :: Nil)

  /**
   * This is the way to cooperatively finish a swap. The maker refunds to its address without waiting for the CSV.
   * witness: <signature_for_taker> <signature_for_maker> <> <redeem_script>
   */
  def witnessClaimByCoop(takerSig: ByteVector64, makerSig: ByteVector64, redeemScript: ByteVector): ScriptWitness =
    ScriptWitness(der(takerSig) :: der(makerSig) :: ByteVector.empty :: redeemScript :: Nil)

  /**
   * This is the way to finish a swap if the invoice was not paid and the taker did not send a coop_close message. After the relative locktime has passed, the maker refunds to them.
   * witness: <signature_for_maker> <redeem_script>
   */
  def witnessClaimByCsv(makerSig: ByteVector64, redeemScript: ByteVector): ScriptWitness =
    ScriptWitness(der(makerSig) :: redeemScript :: Nil)
}
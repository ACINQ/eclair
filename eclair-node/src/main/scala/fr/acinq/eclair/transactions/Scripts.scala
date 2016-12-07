package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{BinaryData, OP_2, OP_CHECKLOCKTIMEVERIFY, OP_CHECKMULTISIG, OP_CHECKSEQUENCEVERIFY, OP_CHECKSIG, OP_DROP, OP_ELSE, OP_ENDIF, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_IF, OP_NOTIF, OP_PUSHDATA, OP_SIZE, OP_SWAP, Script}

/**
  * Created by PM on 02/12/2016.
  */
object OutputScripts {

  def toLocal(revocationPubKey: BinaryData, toSelfDelay: Long, localDelayedKey: BinaryData) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubKey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

  def toRemote(remoteKey: BinaryData) = remoteKey

  def htlcOffered(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_NOTIF ::
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcReceived(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIG ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIG ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcSuccessOrTimeout(revocationPubKey: BinaryData, toSelfDelay: Long, localDelayedKey: BinaryData) = {
    // @formatter:off
    OP_IF ::
      OP_PUSHDATA(revocationPubKey) ::
    OP_ELSE ::
      OP_PUSHDATA(Script.encodeNumber(toSelfDelay)) :: OP_CHECKSEQUENCEVERIFY :: OP_DROP ::
      OP_PUSHDATA(localDelayedKey) ::
    OP_ENDIF ::
    OP_CHECKSIG :: Nil
    // @formatter:on
  }

}

object InputScripts {

}

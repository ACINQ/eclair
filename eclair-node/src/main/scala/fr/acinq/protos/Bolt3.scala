package fr.acinq.protos

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.Scripts

object Bolt3 {
  // TODO: sort tx according to BIP69 (lexicographical ordering)

  def fundingScript(pubKey1: BinaryData, pubKey2: BinaryData) = Scripts.multiSig2of2(pubKey1, pubKey2)

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
      OP_DROP :: OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIGVERIFY ::
    OP_ELSE ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_CHECKSIGVERIFY ::
    OP_ENDIF :: Nil
    // @formatter:on
  }

  def htlcReceived(localKey: BinaryData, remoteKey: BinaryData, paymentHash: BinaryData, lockTime: Long) = {
    // @formatter:off
    OP_PUSHDATA(remoteKey) :: OP_SWAP ::
    OP_SIZE :: OP_PUSHDATA(Script.encodeNumber(32)) :: OP_EQUAL ::
    OP_IF ::
      OP_HASH160 :: OP_PUSHDATA(paymentHash) :: OP_EQUALVERIFY ::
      OP_2 :: OP_SWAP :: OP_PUSHDATA(localKey) :: OP_2 :: OP_CHECKMULTISIGVERIFY ::
    OP_ELSE ::
      OP_DROP :: OP_PUSHDATA(Script.encodeNumber(lockTime)) :: OP_CHECKLOCKTIMEVERIFY :: OP_DROP :: OP_CHECKSIGVERIFY ::
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

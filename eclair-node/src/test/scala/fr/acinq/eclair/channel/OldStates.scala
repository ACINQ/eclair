package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Transaction}
import lightning.{close_shutdown, close_signature, open_complete, sha256_hash}

/**
  * Created by PM on 25/11/2016.
  */

// @formatter:off
final case class DATA_OPEN_WAIT_FOR_OPEN(ourParams: OurChannelParams) extends Data
final case class DATA_OPEN_WITH_ANCHOR_WAIT_FOR_ANCHOR(ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: BinaryData, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: sha256_hash, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, initialCommitment: TheirCommit, theirNextRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING(commitments: Commitments, deferred: Option[open_complete]) extends Data with HasCommitments
final case class DATA_NORMAL(commitments: Commitments,
                             ourShutdown: Option[close_shutdown],
                             downstreams: Map[Long, Option[Origin]]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(commitments: Commitments,
                               ourShutdown: close_shutdown, theirShutdown: close_shutdown,
                               downstreams: Map[Long, Option[Origin]]) extends Data with HasCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  ourShutdown: close_shutdown, theirShutdown: close_shutdown, ourSignature: close_signature) extends Data with HasCommitments
final case class DATA_CLOSING(commitments: Commitments,
                              ourSignature: Option[close_signature] = None,
                              mutualClosePublished: Option[Transaction] = None,
                              ourCommitPublished: Option[Transaction] = None,
                              theirCommitPublished: Option[Transaction] = None,
                              revokedPublished: Seq[Transaction] = Seq()) extends Data with HasCommitments {
  assert(mutualClosePublished.isDefined || ourCommitPublished.isDefined || theirCommitPublished.isDefined || revokedPublished.size > 0, "there should be at least one tx published in this state")
}

// @formatter:on
package fr.acinq.protos.bilateralcommit

import fr.acinq.eclair.transactions.{Direction, IN, OUT}

/**
  * Created by PM on 18/08/2016.
  */

case class Change(dir: Direction, c: String) {
  def reverse(): Change = dir match {
    case IN => copy(dir = OUT)
    case OUT => copy(dir = IN)
  }
}

case class Commitments(ourchanges_proposed: List[Change] = Nil,
                       ourchanges_signed: List[Change] = Nil,
                       ourchanges_acked: List[Change] = Nil,
                       theirchanges_unacked: List[Change] = Nil,
                       theirchanges_acked: List[Change] = Nil,
                       our_commit: Set[Change] = Set.empty,
                       their_commit: Set[Change] = Set.empty,
                       waitingForRev: Boolean = false) {

  def sendChange(c: String): Commitments = {
    this.copy(
      ourchanges_proposed = ourchanges_proposed :+ Change(OUT, c)
    )
  }

  def receiveChange(c: String): Commitments = {
    this.copy(
      theirchanges_unacked = theirchanges_unacked :+ Change(IN, c)
    )
  }

  //
  def compactSig(sig: Set[Change]): Set[Change] = {
    val ff = sig.filter(_.c.startsWith("F"))
    val aa = ff.map(_.reverse()).map(change => change.copy(c = change.c.replace("F", "A")))
    val cleaned = sig -- aa -- ff
    assert(cleaned.size == sig.size - 2 * ff.size, s"sig=$sig aa = $aa ff=$ff cleaned=$cleaned")
    cleaned
  }

  def sendSig(): Commitments = {
    assert(waitingForRev == false)
    this.copy(
      ourchanges_proposed = Nil,
      ourchanges_signed = ourchanges_proposed,
      theirchanges_acked = Nil,
      their_commit = compactSig(their_commit ++ (ourchanges_proposed ++ theirchanges_acked).map(_.reverse)),
      waitingForRev = true)
  }

  def receiveSig(): Commitments = {
    this.copy(
      ourchanges_acked = Nil,
      theirchanges_unacked = Nil,
      theirchanges_acked = theirchanges_acked ++ theirchanges_unacked,
      our_commit = compactSig(our_commit ++ ourchanges_acked ++ theirchanges_unacked)
    )
  }

  def receiveRev(): Commitments = {
    this.copy(
      ourchanges_signed = Nil,
      ourchanges_acked = ourchanges_acked ++ ourchanges_signed,
      waitingForRev = false
    )
  }

  override def toString: String =
    "OUR COMMIT\n" +
      s"ourchanges_proposed=$ourchanges_proposed\n" +
      s"theirchanges_acked=$theirchanges_acked\n" +
      s"our_commit=$our_commit}n" +
      "THEIR COMMIT\n" +
      s"ourchanges_signed=$ourchanges_signed\n" +
      s"ourchanges_acked=$ourchanges_acked\n" +
      s"theirchanges_unacked=$theirchanges_unacked\n" +
      s"their_commit=$their_commit\n"
}

object BilateralCommitTest extends App {

  var commitments = Commitments()
  commitments = commitments.sendChange("A1")
  println(commitments)
  commitments = commitments.sendSig()
  println(commitments)
  commitments = commitments.receiveRev()
  println(commitments)
  commitments = commitments.receiveChange("F1")
  println(commitments)
  commitments = commitments.receiveSig()
  println(commitments)

}
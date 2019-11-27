package fr.acinq.eclair.db

import java.io.File

import scala.concurrent.duration._
import akka.actor.{Actor, ActorLogging, Props}
import com.google.common.io.Files
import fr.acinq.bitcoin.{ByteVector64, Crypto}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.ChannelPersisted
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext

class AkkaBackupHandler(nodeParams: NodeParams, incomingNodeIds: Set[PublicKey], outgoingNodeAddresses: Set[String], localBackupFile: File, remoteBackupDir: File, throttle: Int)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(throttle.seconds, throttle.seconds, self, AkkaBackupHandler.SEND_AKKA_BACKUP_TO_REMOTE_NODE)

  // we listen to ChannelPersisted events, which will schedule a backup
  context.system.eventStream.subscribe(self, classOf[ChannelPersisted])

  if (!remoteBackupDir.exists()) {
    remoteBackupDir.mkdir()
  }

  override def receive: Receive = main(None)

  def main(akkaBackupOpt: Option[AkkaBackup] = None): Receive = {
    case ab: AkkaBackup if incomingNodeIds.contains(ab.remoteNodeId) && ab.isValidSig =>
      val backupFile = new File(remoteBackupDir.getAbsolutePath, ab.remoteNodeId.toString.concat(".eclair.sqlite.bak"))
      Files.write(ab.data.toArray, backupFile)

    case persisted: ChannelPersisted =>
      val timing = BackupHandler.makeBackup(nodeParams.db, localBackupFile)
      // publish a notification that we have updated our backup
      context.system.eventStream.publish(BackupCompleted)
      log.info(s"akka database backup scheduled by channelId=${persisted.channelId} took ${timing}ms")
      val backup = ByteVector.view(Files.toByteArray(localBackupFile))
      val localSig = Crypto.sign(Crypto.sha256(backup), nodeParams.privateKey)
      val ab = AkkaBackup(backup, nodeParams.nodeId, localSig)
      context become main(Some(ab))

    case AkkaBackupHandler.SEND_AKKA_BACKUP_TO_REMOTE_NODE =>
      akkaBackupOpt.foreach(remoteSend)
      context become main(None)
  }

  def remoteSend(message: Any): Unit =
    for {
      addressAndPort <- outgoingNodeAddresses
      path = s"akka.ssl.tcp://${context.system.name}@$addressAndPort/user/${AkkaBackupHandler.actorName}"
    } context.actorSelection(path) ! message
}

case class AkkaBackup(data: ByteVector, remoteNodeId: PublicKey, signature: ByteVector64) {
  def isValidSig: Boolean = Crypto.verifySignature(Crypto.sha256(data), signature, remoteNodeId)
}

object AkkaBackupHandler {
  final val actorName = "akkaBackupHandler"

  case object SEND_AKKA_BACKUP_TO_REMOTE_NODE

  def props(nodeParams: NodeParams, incomingNodeIds: Set[PublicKey], outgoingNodeAddresses: Set[String], localBackupFile: File, remoteBackupDir: File, throttle: Int) =
    Props(new AkkaBackupHandler(nodeParams, incomingNodeIds, outgoingNodeAddresses, localBackupFile, remoteBackupDir, throttle))
}
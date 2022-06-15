/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{DiagnosticLoggingAdapter, EventStream}
import akka.testkit.{TestActor, TestProbe}
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.wire.protocol.LightningMessage
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object TestUtils {

  /**
   * Get the module's target directory (works from command line and within intellij)
   */
  val BUILD_DIRECTORY = sys
    .props
    .get("buildDirectory") // this is defined if we run from maven
    .getOrElse(new File(sys.props("user.dir"), "eclair-core/target").getAbsolutePath) // otherwise we probably are in intellij, so we build it manually assuming that user.dir == path to the module

  def availablePort: Int = synchronized {
    var serverSocket: ServerSocket = null
    try {
      serverSocket = new ServerSocket(0)
      serverSocket.getLocalPort
    } finally {
      if (serverSocket != null) {
        serverSocket.close()
      }
    }
  }

  def newIntegrationTmpDir(baseDir: String = TestUtils.BUILD_DIRECTORY) = new File(baseDir, s"integration-${UUID.randomUUID()}")

  object NoLoggingDiagnostics extends DiagnosticLoggingAdapter {
    // @formatter:off
    override def isErrorEnabled: Boolean = false
    override def isWarningEnabled: Boolean = false
    override def isInfoEnabled: Boolean = false
    override def isDebugEnabled: Boolean = false
    override protected def notifyError(message: String): Unit = ()
    override protected def notifyError(cause: Throwable, message: String): Unit = ()
    override protected def notifyWarning(message: String): Unit = ()
    override protected def notifyInfo(message: String): Unit = ()
    override protected def notifyDebug(message: String): Unit = ()
    // @formatter:on
  }

  /**
   * [[Channel]] encapsulates outgoing messages in [[Peer.OutgoingMessage]] due to how connection management works.
   *
   * This strips the [[Peer.OutgoingMessage]] outer shell and only forwards the inner [[LightningMessage]] making testing
   * easier. You can now pass a [[TestProbe]] as a connection and only deal with incoming/outgoing [[LightningMessage]].
   */
  def forwardOutgoingToPipe(peer: TestProbe, pipe: ActorRef): Unit = {
    peer.setAutoPilot((sender: ActorRef, msg: Any) => msg match {
      case Peer.OutgoingMessage(msg: LightningMessage, _: ActorRef) =>
        pipe.tell(msg, sender)
        TestActor.KeepRunning
      case _ => TestActor.KeepRunning
    })
  }

  def createSeedFile(name: String, seed: Array[Byte]): File = {
    val seedDir = new File(newIntegrationTmpDir(), s"seed-migration")
    seedDir.mkdirs()
    val seedFile = new File(seedDir, name)
    Files.write(seedFile.toPath, seed)
    seedFile
  }

  /**
   * Subscribing to [[EventStream]] is asynchronous, which can lead to race conditions.
   *
   * We use a dummy event subscription and poll until we receive a message, and rely on the fact that
   * [[EventStream]] is an actor which means that all previous subscriptions have been taken into account.
   */
  def waitEventStreamSynced(eventStream: EventStream)(implicit system: ActorSystem): Unit = {
    val listener = TestProbe()
    case class DummyEvent()
    eventStream.subscribe(listener.ref, classOf[DummyEvent])
    eventually {
      eventStream.publish(DummyEvent())
      assert(listener.msgAvailable)
    }
  }

  def waitFor(duration: FiniteDuration): Unit = Thread.sleep(duration.toMillis)

}

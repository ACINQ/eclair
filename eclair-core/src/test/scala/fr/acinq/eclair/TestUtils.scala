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

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

import akka.actor.ActorRef
import akka.event.DiagnosticLoggingAdapter
import akka.testkit
import akka.testkit.{TestActor, TestProbe}
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.wire.LightningMessage

object TestUtils {

  /**
    * Get the module's target directory (works from command line and within intellij)
    */
  val BUILD_DIRECTORY = sys
    .props
    .get("buildDirectory") // this is defined if we run from maven
    .getOrElse(new File(sys.props("user.dir"), "target").getAbsolutePath) // otherwise we probably are in intellij, so we build it manually assuming that user.dir == path to the module

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
    override def isErrorEnabled: Boolean = false
    override def isWarningEnabled: Boolean = false
    override def isInfoEnabled: Boolean = false
    override def isDebugEnabled: Boolean = false

    override protected def notifyError(message: String): Unit = ()
    override protected def notifyError(cause: Throwable, message: String): Unit = ()
    override protected def notifyWarning(message: String): Unit = ()
    override protected def notifyInfo(message: String): Unit = ()
    override protected def notifyDebug(message: String): Unit = ()
  }


  /**
   * [[Channel]] encapsulates outgoing messages in [[Channel.OutgoingMessage]] due to how connection management works.
   *
   * This strips the [[Channel.OutgoingMessage]] outer shell and only forwards the inner [[LightningMessage]] making testing
   * easier. You can now pass a [[TestProbe]] as a connection and only deal with incoming/outgoing [[LightningMessage]].
   */
  def forwardOutgoingToPipe(peer: TestProbe, pipe: ActorRef): Unit = {
    peer.setAutoPilot(new testkit.TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = msg match {
        case Channel.OutgoingMessage(msg: LightningMessage, _: ActorRef) =>
          pipe.tell(msg, sender)
          TestActor.KeepRunning
        case _ => TestActor.KeepRunning
      }
    })
  }

  def createSeedFile(name: String, seed: Array[Byte]): File = {
    val seedDir = new File(newIntegrationTmpDir(), s"seed-migration")
    seedDir.mkdirs()
    val seedFile = new File(seedDir, name)
    Files.write(seedFile.toPath, seed)
    seedFile
  }

}

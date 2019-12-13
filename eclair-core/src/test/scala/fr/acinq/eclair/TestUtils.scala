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

import akka.event.DiagnosticLoggingAdapter

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

}

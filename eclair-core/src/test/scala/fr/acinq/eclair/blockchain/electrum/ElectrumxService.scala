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

package fr.acinq.eclair.blockchain.electrum

import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.whisk.docker.impl.spotify.SpotifyDockerFactory
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerFactory}
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import org.scalatest.Suite

import scala.concurrent.duration.DurationInt

trait ElectrumxService extends DockerTestKit {
  self: Suite with BitcoindService =>

  val electrumPort = TestUtils.availablePort

  val electrumxContainer = if (System.getProperty("os.name").startsWith("Linux")) {
    // "host" mode will let the container access the host network on linux
    // we use our own docker image because other images on Docker lag behind and don't yet support 1.4
    DockerContainer("acinq/electrumx")
      .withNetworkMode("host")
      .withEnv(s"DAEMON_URL=http://foo:bar@localhost:$bitcoindRpcPort", "COIN=BitcoinSegwit", "NET=regtest", s"TCP_PORT=$electrumPort")
      //.withLogLineReceiver(LogLineReceiver(true, println))
  } else {
    // on windows or oxs, host mode is not available, but from docker 18.03 on host.docker.internal can be used instead
    // host.docker.internal is not (yet ?) available on linux though
    DockerContainer("acinq/electrumx")
      .withPorts(electrumPort -> Some(electrumPort))
      .withEnv(s"DAEMON_URL=http://foo:bar@host.docker.internal:$bitcoindRpcPort", "COIN=BitcoinSegwit", "NET=regtest", s"TCP_PORT=$electrumPort")
      //.withLogLineReceiver(LogLineReceiver(true, println))
  }

  //override DockerKit timeouts
  override val StartContainersTimeout = 60 seconds

  override val StopContainersTimeout = 60 seconds

  override def dockerContainers: List[DockerContainer] = electrumxContainer :: super.dockerContainers

  private val client: DockerClient = DefaultDockerClient.fromEnv().build()

  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)
}

/*
 * Copyright 2018 ACINQ SAS
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
import com.whisk.docker.{DockerContainer, DockerFactory, LogLineReceiver}
import org.scalatest.Suite

trait ElectrumxService extends DockerTestKit {
  self: Suite =>

  val electrumxContainer = DockerContainer("lukechilds/electrumx")
    .withNetworkMode("host")
    .withEnv("DAEMON_URL=http://foo:bar@localhost:28332", "COIN=BitcoinSegwit", "NET=regtest")
    .withLogLineReceiver(LogLineReceiver(true, println))

  override def dockerContainers: List[DockerContainer] = electrumxContainer :: super.dockerContainers

  private val client: DockerClient = DefaultDockerClient.fromEnv().build()

  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)
}

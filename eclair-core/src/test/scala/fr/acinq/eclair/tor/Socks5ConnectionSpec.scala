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

package fr.acinq.eclair.tor

import fr.acinq.eclair.wire.protocol.NodeAddress
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress

/**
  * Created by PM on 27/01/2017.
  */

class Socks5ConnectionSpec extends AnyFunSuite {

  test("get proxy address") {
    val proxyAddress = new InetSocketAddress(9050)

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("1.2.3.4", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).contains(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("1.2.3.4", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = false, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).isEmpty)

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("[fc92:97a3:e057:b290:abd8:9bd6:135d:7e7]", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).contains(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("[fc92:97a3:e057:b290:abd8:9bd6:135d:7e7]", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = false, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).isEmpty)

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).contains(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = false, useForWatchdogs = true, useForDnsHostnames = true)).isEmpty)

    // DnsHostname "localhost" resolves to an IPv4 address
    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("localhost", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = true)).contains(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      address = NodeAddress.fromParts("localhost", 9735).get,
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true, useForWatchdogs = true, useForDnsHostnames = false)).isEmpty)
  }

}

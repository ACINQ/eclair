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

package fr.acinq.eclair.tor

import java.net.InetSocketAddress

import org.scalatest.FunSuite

/**
  * Created by PM on 27/01/2017.
  */

class Socks5ConnectionSpec extends FunSuite {

  test("get proxy address") {
    val proxyAddress = new InetSocketAddress(9050)

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("1.2.3.4", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true)) == Some(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("1.2.3.4", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = false, useForIPv6 = true, useForTor = true)) == None)

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("[fc92:97a3:e057:b290:abd8:9bd6:135d:7e7]", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true)) == Some(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("[fc92:97a3:e057:b290:abd8:9bd6:135d:7e7]", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = false, useForTor = true)) == None)

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = true)) == Some(proxyAddress))

    assert(Socks5ProxyParams.proxyAddress(
      socketAddress = new InetSocketAddress("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735),
      proxyParams = Socks5ProxyParams(address = proxyAddress, credentials_opt = None, randomizeCredentials = false, useForIPv4 = true, useForIPv6 = true, useForTor = false)) == None)


  }

}

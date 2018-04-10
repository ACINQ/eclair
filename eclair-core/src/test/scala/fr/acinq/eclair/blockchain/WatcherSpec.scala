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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.Transaction
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class WatcherSpec extends FunSuite {

  test("extract pay2wpkh pubkey script") {
    val commitTx = Transaction.read("020000000001010ba75314a116c1e585d1454d079598c5f00edc8a21ebd9e4f3b64e5c318ff2a30100000000e832a680012e850100000000001600147d2a3fc37dba8e946e0238d7eeb6fb602be658200400473044022010d4f249861bb9828ddfd2cda91dc10b8f8ffd0f15c8a4a85a2d373d52f5e0ff02205356242878121676e3e823ceb3dc075d18fed015053badc8f8d754b8959a9178014730440220521002cf241311facf541b689e7229977bfceffa0e4ded785b4e6197af80bfa202204a168d1f7ee59c73ae09c3e0a854b20262b9969fe4ed69b15796dca3ea286582014752210365375134360808be0b4756ba8a2995488310ac4c69571f2b600aaba3ec6cc2d32103a0d9c18794f16dfe01d6d6716bcd1e97ecff2f39451ec48e1899af40f20a18bc52aec3dd9520")
    val claimMainTx = Transaction.read("020000000001012537488e9d066a8f3550cc9adc141a11668425e046e69e07f53bb831f3296cbf00000000000000000001bf8401000000000017a9143f398d81d3c42367b779ea869c7dd3b6826fbb7487024730440220477b961f6360ef6cb62a76898dcecbb130627c7e6a452646e3be601f04627c1f02202572313d0c0afecbfb0c7d0e47ba689427a54f3debaded6d406daa1f5da4918c01210291ed78158810ad867465377f5920036ea865a29b3a39a1b1808d0c3c351a4b4100000000")

    assert(commitTx.txOut.head.publicKeyScript === WatchConfirmed.extractPublicKeyScript(claimMainTx.txIn.head.witness))
  }

  test("extract pay2wsh pubkey script") {
    val commitTx = Transaction.read("02000000000101fb98507ff5f47bcc5b4497a145e631f68b2b5fcf2752598bc54c8f33696e1c73000000000017f15b80015b3f0f0000000000220020345fc26988f6252d9d93ee95f2198e820db1a4d7c7ec557e4cc5d7e60750cc21040047304402202fd9cbc8446a10193f378269bf12d321aa972743c0a011089aff522de2a1414d02204dd65bf43e41fe911c7180e5e036d609646a798fa5c3f288ede73679978df36b01483045022100fced8966c2527cb175521c4eb41aaaee96838420fa5fce3d4730c0da37f6253502202dc9667530a9f79bc6444b54335467d2043c4b996da5fbca7496e0fa64ccc1bd0147522103a16c06d8626bad5d6d8ea8fee980c287590b9dedeb5857a3d0cd6c4b4e95631c2103d872e26e43f723523d2d8eff5f93a1b344fe51eb76bcfd4906315ae2fe35389a52ae620acc20")
    val claimMainDelayedTx = Transaction.read("02000000000101b285ffeb84c366f621fe33b6ff77a9b7578075b65e69c363d12c35aa422d98fd00000000009000000001e03e0f000000000017a9147407522166f1ed3030788b1b6a48803867d1797f8703483045022100fe9eefd010a80411ccae87590db3f54c1c04605170bdcd83c1e04222d474ef41022036db7fd3c07c0523c2cf72d80c7fe3bdc2d5028a8bc2864b478a707e8af627dc01004d63210298f7dada89d882c4ab971e7e914f4953249bad70333b29aa504bb67e5ce9239c67029000b275210328170f7e781c70ea679efc30383d3e03451ca350e2a8690f8ed3db9dabb3866768ac00000000")

    assert(commitTx.txOut.head.publicKeyScript === WatchConfirmed.extractPublicKeyScript(claimMainDelayedTx.txIn.head.witness))
  }


}

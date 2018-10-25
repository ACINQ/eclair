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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.{BinaryData, Hash}
import org.scalatest.FunSuite


class ShaChainSpec extends FunSuite {
  val expected: List[BinaryData] = List(
    "f85a0f7f237ed2139855703db4264de380ec731f64a3d832c22a5f2ef615f1d5",
    "a07acb1203f8d7a761eb43e109e46fd877031a6fd2a8e6840f064a49ba826aec",
    "364a7bd51430602eb967b86d33e2cbd4d5e711eb098c08fd8c6d0328bb1efeb2",
    "f1c239ec1354d2ee136c00d4f73d9e3ea099c6e722578061f814d8cd220e57ad",
    "3253b933532f7ea8bffa6327421fd485e78ce5ea3309f157881f824c57d7df35",
    "dee0cc9ed8447b50ff31a7122f7fdb7e3882dfba7cc9572619b18887c2e34e88",
    "07b1c6670de153ec0d1360508de82cc587a693d68f77c15c12439dd89e796887",
    "b7f66c275eb2977369106424a4f48086ddc23c00db5dcaf7113b54868e03a2d6",
    "f49d078e11f163699e4a9eb8072d9ff1524264d7806952c05aedc1f363d35762",
    "0f1f739542deeb52b741c1f3a0f0e084332a26560e62677d5277013174718523",
    "531bd399ddcdbe2e7c8392eff55b7466cf5e1baf2d62b8b6afae09ff3f591545",
    "77eda8546aa74bfaed5b66cde07fbd22dd38a3db021588d94c95a82028c6ff7f",
    "ece8b33e11cb3e0e92002a320f6ca75dac22241cacfe06dc15ec4037dc86114b",
    "cbf53a35e3b96d135b80ed09cebdc3fa4a357a873a29c386922baca7173bd8de",
    "facc71a4c2558620591c22e196f5d3aca3adb39a3a8ce919820995333b32a979",
    "bcee71cc4caf6482918a5242b7068ad563a0ec32d2a20f53215101dd35ad57cb",
    "d4e8fc62baf5c028ce3a2c678cf49c289027838ab548f3148eaf003c012daa06",
    "01a33aa5a56f42e7c7e7b542f70fdf5c03b47491002a1a1ad3560495768dc243",
    "91e8a298b9486f11751636ad568a5f76fcc62d0530df79f5fb67f0fd8089bbb6",
    "b03e0cef62bc8809d71acaef01749931a3305ac0448259ee2bb199aa307b3e2b",
    "306d8f23361667f2a95fe3f92b0af8191b4de4ffe88cb1516b26c1aad4014980",
    "b73edf619d01bb2230399c95c5a712ba71d6ae8829a71424e0e01e11ae98153e",
    "45ffa278d66a7081c8473dbe69431cbb80f026b33bede3420484aa325f58be14",
    "b1804267ad0eb2235b2a058ca920af9fa7b17d8ef83515fdaed373e1a38841c6",
    "34ca9f1373205595c8a2f46e2b1c3c633c173b85176cc94a5f8b7573a4e2932a",
    "3b3dba43cc19265db2343d6d61f15ee742d1f5f4cf930d653f50f47b6c4b5113",
    "7c8cc4d61b8d08d94596e4a6e80ba5203afba7d28beb54d26b4e577440ffdf44",
    "4ada9547a2d8dd24b3e3816cf2a3282ab5b1738631aa15546465427e3d9b877b",
    "5b0fd6c6325d83e4523804757eac4d8f7d322f580ed7948590021988285d3e45",
    "5787424f4dad5c4821d84415046fb880a45e039d58224bb912bf227aa76a85fe",
    "decb676db1b2dcdb94cde832df8b9b89fdbbee2a1bd0cb0393cabdfcdd161a3f",
    "7cab4b12ffe27b76794dedd7ed792997fc9f70a3dabcb692b4834d665c1079da",
    "8e7b1bc987fe0f429b7d30734c98b786d2e39a4c16dcae5346f81a484eda5d10",
    "d0426719692922e79d14d8124a44887fe4d00928f6ee68de89296616c93cfd02",
    "5d87dec9d2c20aa073bc9853217133d2aa2571680b67ba8ccd56268d04d5bb32",
    "3d932173ef3526c2ed1615d396911faa1569de3053b1b5a8d80f20d34deb6db1",
    "897b02ad591bf12b2393e1759b8bf350ebc84a0b2f96be430c97d9791434394d",
    "64c62643cd141f822912c02308b43ebab8e176e90a408c3f927a57ae2b865210",
    "ed39b7046d98b8ddb597ad9cb34e216c9fd49b181e88393e7683e661eafcf9f1",
    "de03f8d4e4e2d4f34f89752e914636cc8f3eb63a237ce0a6131b02e9bad8f68f",
    "42e3d3ca2772955a71c893ed17db43f2557df988abab017777c039764067ea6f",
    "e1d9cc50aa847b8e48181f9a2b14e41ad95819f93326fdf563ae638231f8aeb6",
    "6fbe29e9bcd7c22c819af36df885697c7445f2de5457048d3e2c450aa2623cbb",
    "2273be59ca2eef6a18a83f095c46d97f662a0c8a8b7e36b5be4e01e2fef8b355",
    "47c64154fddba56dfee924b29d098cc95ecd76887ef541553b2b1157a3b5e9e1",
    "6a139481ff4095040e397c71b93fd556b35ff3d149ec86d5611703f2a84979bb",
    "d621ad4edbe0db4502dbec1086afcf267ba7642320d9c2b8e0622da0c1ccf97d",
    "145c7f90baa79843dd78b1ad0c3671d974296ee910c56e935b1faa36230927db",
    "0c73aa6bd28175c4b6545501e8ce51492a98e53027b8137008359f6d750d2f38",
    "01a10b1efc3071b46284fd9b79c16999d3d0dcad88fb17bdf3cbfaeb6251ecde")
  val seed = Hash.Zeroes

  test("generate and receive hashes") {
    val result: List[BinaryData] = (for (i <- 0 until 50) yield ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - i)).toList
    assert(result === expected)

    var receiver = ShaChain.empty
    for (i <- 0 until 1000) {
      receiver = receiver.addHash(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - i), 0xFFFFFFFFFFFFFFFFL - i)
      assert(receiver.knownHashes.size <= 64)
      var j: Long = 0xFFFFFFFFFFFFFFFFL - i
      while (j != 0xFFFFFFFFFFFFFFFFL) {
        val ho = receiver.getHash(j)
        assert(ho.isDefined)
        val k = (0xFFFFFFFFFFFFFFFFL - j).toInt
        if (k < 50) assert(ho == Some(expected(k)))
        j = j + 1
      }
      assert(receiver.getHash(0xFFFFFFFFFFFFFFFFL - i - 1) == None)
    }
  }

  test("provide iterators") {
    var receiver = ShaChain.empty
    for (i <- 0 until 50) {
      receiver = receiver.addHash(ShaChain.shaChainFromSeed(seed, 0xFFFFFFFFFFFFFFFFL - i), 0xFFFFFFFFFFFFFFFFL - i)
    }
    assert(receiver.iterator.toList == expected.reverse)

    assert(receiver.iterator.find(_ == expected(20)) == Some(expected(20)))
  }

  test("Rusty's reference tests: generation") {
    assert(ShaChain.shaChainFromSeed(BinaryData("0000000000000000000000000000000000000000000000000000000000000000"), 281474976710655L) == BinaryData("02a40c85b6f28da08dfdbe0926c53fab2de6d28c10301f8f7c4073d5e42e3148"))
    assert(ShaChain.shaChainFromSeed(BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), 281474976710655L) == BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"))
    assert(ShaChain.shaChainFromSeed(BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), 0xaaaaaaaaaaaL) == BinaryData("56f4008fb007ca9acf0e15b054d5c9fd12ee06cea347914ddbaed70d1c13a528"))
    assert(ShaChain.shaChainFromSeed(BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), 0x555555555555L) == BinaryData("9015daaeb06dba4ccc05b91b2f73bd54405f2be9f217fbacd3c5ac2e62327d31"))
    assert(ShaChain.shaChainFromSeed(BinaryData("0101010101010101010101010101010101010101010101010101010101010101"), 1) == BinaryData("915c75942a26bb3a433a8ce2cb0427c29ec6c1775cfc78328b57f6ba7bfeaa9c"))
  }

  test("Rusty's reference tests: insert_secret correct sequence") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("c65716add7aa98ba7acb236352d665cab17345fe45b55fb879ff80e6bd0c41dd"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("a5a64476122ca0925fb344bdc1854c1c0a59fc614298e50a33e331980a220f32"), 281474976710649L)
    val chain8 = chain7.addHash(BinaryData("05cde6323d949933f7f7b78776bcc1ea6d9b31447732e3802e1f7ac44b650e17"), 281474976710648L)
  }

  test("Rusty's reference tests: insert_secret #1 incorrect") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("02a40c85b6f28da08dfdbe0926c53fab2de6d28c10301f8f7c4073d5e42e3148"), 281474976710655L)
    intercept[IllegalArgumentException] {
      val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    }
  }

  test("Rusty's reference tests: insert_secret #2 incorrect (#1 derived from incorrect)") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("02a40c85b6f28da08dfdbe0926c53fab2de6d28c10301f8f7c4073d5e42e3148"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("dddc3a8d14fddf2b68fa8c7fbad2748274937479dd0f8930d5ebb4ab6bd866a3"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    intercept[IllegalArgumentException] {
      val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    }
  }

  test("Rusty's reference tests: insert_secret #3 incorrect") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("c51a18b13e8527e579ec56365482c62f180b7d5760b46e9477dae59e87ed423a"), 281474976710653L)
    intercept[IllegalArgumentException] {
      val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    }
  }

  test("Rusty's reference tests: insert_secret #4 incorrect (1,2,3 derived from incorrect)") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("02a40c85b6f28da08dfdbe0926c53fab2de6d28c10301f8f7c4073d5e42e3148"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("dddc3a8d14fddf2b68fa8c7fbad2748274937479dd0f8930d5ebb4ab6bd866a3"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("c51a18b13e8527e579ec56365482c62f180b7d5760b46e9477dae59e87ed423a"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("ba65d7b0ef55a3ba300d4e87af29868f394f8f138d78a7011669c79b37b936f4"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("c65716add7aa98ba7acb236352d665cab17345fe45b55fb879ff80e6bd0c41dd"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("a5a64476122ca0925fb344bdc1854c1c0a59fc614298e50a33e331980a220f32"), 281474976710649L)
    intercept[IllegalArgumentException] {
      val chain8 = chain7.addHash(BinaryData("05cde6323d949933f7f7b78776bcc1ea6d9b31447732e3802e1f7ac44b650e17"), 281474976710648L)
    }
  }

  test("Rusty's reference tests: insert_secret #5 incorrect") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("631373ad5f9ef654bb3dade742d09504c567edd24320d2fcd68e3cc47e2ff6a6"), 281474976710651L)
    intercept[IllegalArgumentException] {
      val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    }
  }

  test("Rusty's reference tests: insert_secret #6 incorrect (5 derived from incorrect)") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("631373ad5f9ef654bb3dade742d09504c567edd24320d2fcd68e3cc47e2ff6a6"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("b7e76a83668bde38b373970155c868a653304308f9896692f904a23731224bb1"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("a5a64476122ca0925fb344bdc1854c1c0a59fc614298e50a33e331980a220f32"), 281474976710649L)
    intercept[IllegalArgumentException] {
      val chain8 = chain7.addHash(BinaryData("05cde6323d949933f7f7b78776bcc1ea6d9b31447732e3802e1f7ac44b650e17"), 281474976710648L)
    }
  }

  test("Rusty's reference tests: insert_secret #7 incorrect") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("c65716add7aa98ba7acb236352d665cab17345fe45b55fb879ff80e6bd0c41dd"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("e7971de736e01da8ed58b94c2fc216cb1dca9e326f3a96e7194fe8ea8af6c0a3"), 281474976710649L)
    intercept[IllegalArgumentException] {
      val chain8 = chain7.addHash(BinaryData("05cde6323d949933f7f7b78776bcc1ea6d9b31447732e3802e1f7ac44b650e17"), 281474976710648L)
    }
  }

  test("Rusty's reference tests: insert_secret #8 incorrect") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("c65716add7aa98ba7acb236352d665cab17345fe45b55fb879ff80e6bd0c41dd"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("a5a64476122ca0925fb344bdc1854c1c0a59fc614298e50a33e331980a220f32"), 281474976710649L)
    intercept[IllegalArgumentException] {
      val chain8 = chain7.addHash(BinaryData("a7efbc61aac46d34f77778bac22c8a20c6a46ca460addc49009bda875ec88fa4"), 281474976710648L)
    }
  }

  test("serialize/deserialize with scodec") {
    val chain = ShaChain.init
    val chain1 = chain.addHash(BinaryData("7cc854b54e3e0dcdb010d7a3fee464a9687be6e8db3be6854c475621e007a5dc"), 281474976710655L)
    val chain2 = chain1.addHash(BinaryData("c7518c8ae4660ed02894df8976fa1a3659c1a8b4b5bec0c4b872abeba4cb8964"), 281474976710654L)
    val chain3 = chain2.addHash(BinaryData("2273e227a5b7449b6e70f1fb4652864038b1cbf9cd7c043a7d6456b7fc275ad8"), 281474976710653L)
    val chain4 = chain3.addHash(BinaryData("27cddaa5624534cb6cb9d7da077cf2b22ab21e9b506fd4998a51d54502e99116"), 281474976710652L)
    val chain5 = chain4.addHash(BinaryData("c65716add7aa98ba7acb236352d665cab17345fe45b55fb879ff80e6bd0c41dd"), 281474976710651L)
    val chain6 = chain5.addHash(BinaryData("969660042a28f32d9be17344e09374b379962d03db1574df5a8a5a47e19ce3f2"), 281474976710650L)
    val chain7 = chain6.addHash(BinaryData("a5a64476122ca0925fb344bdc1854c1c0a59fc614298e50a33e331980a220f32"), 281474976710649L)
    val chain8 = chain7.addHash(BinaryData("05cde6323d949933f7f7b78776bcc1ea6d9b31447732e3802e1f7ac44b650e17"), 281474976710648L)
    Seq(chain, chain1, chain2, chain3, chain4, chain5, chain6, chain7, chain8).map(chain => {
      val encoded = ShaChain.shaChainCodec.encode(chain)
      val decoded = ShaChain.shaChainCodec.decode(encoded.toOption.get).toOption.get.value
      assert(decoded == chain)
    })

  }
}

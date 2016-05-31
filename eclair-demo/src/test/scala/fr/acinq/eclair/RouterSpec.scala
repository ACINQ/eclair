package fr.acinq.eclair

import com.google.protobuf.ByteString
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.Router
import lightning.channel_desc
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouterSpec extends FunSuite {

  test("calculate simple route") {

    val channels: Map[BinaryData, channel_desc] = Map(
      BinaryData("0a") -> channel_desc(BinaryData("0a"), BinaryData("01"), BinaryData("02")),
      BinaryData("0b") -> channel_desc(BinaryData("0b"), BinaryData("03"), BinaryData("02")),
      BinaryData("0c") -> channel_desc(BinaryData("0c"), BinaryData("03"), BinaryData("04")),
      BinaryData("0d") -> channel_desc(BinaryData("0d"), BinaryData("04"), BinaryData("05"))
    )

    val route = Router.findRoute(BinaryData("01"), BinaryData("05"), channels, Seq())

    assert(route === BinaryData("02") :: BinaryData("03") :: BinaryData("04") :: BinaryData("05") :: Nil)

  }

  test("calculate simple route 2") {

    val channels: Map[BinaryData, channel_desc] = Map(
      BinaryData("99e542c274b073d215af02e57f814f3d16a2373a00ac52b49ef4a1949c912609") -> channel_desc(BinaryData("99e542c274b073d215af02e57f814f3d16a2373a00ac52b49ef4a1949c912609"), BinaryData("032b2e37d202658eb5216a698e52da665c25c5d04de0faf1d29aa2af7fb374a003"), BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b")),
      BinaryData("44d7f822e0498e21473a8a40045c9b7e7bd2e78730b5274cb5836e64bc0b6125") -> channel_desc(BinaryData("44d7f822e0498e21473a8a40045c9b7e7bd2e78730b5274cb5836e64bc0b6125"), BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61"), BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b"))
    )

    val route = Router.findRoute(BinaryData("032b2e37d202658eb5216a698e52da665c25c5d04de0faf1d29aa2af7fb374a003"), BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61"), channels, Seq())

    assert(route === BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b") :: BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61") :: Nil)

  }

}

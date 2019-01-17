package fr.acinq.eclair

import java.io.{File, IOException}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.crypto.LocalKeyManager
import org.scalatest.FunSuite

import scala.util.Try

class StartupSpec extends FunSuite {

  test("NodeParams should fail if the alias is illegal (over 32 bytes)") {

    val threeBytesUTFChar = '\u20AC' // €
    val baseUkraineAlias = "BitcoinLightningNodeUkraine"

    assert(baseUkraineAlias.length === 27)
    assert(baseUkraineAlias.getBytes.length === 27)

    // we add 2 UTF-8 chars, each is 3-bytes long -> total new length 33 bytes!
    val goUkraineGo = threeBytesUTFChar+"BitcoinLightningNodeUkraine"+threeBytesUTFChar

    assert(goUkraineGo.length === 29)
    assert(goUkraineGo.getBytes.length === 33) // too long for the alias, should be truncated

    val illegalAliasConf = ConfigFactory.parseString(s"node-alias = $goUkraineGo")
    val conf = illegalAliasConf.withFallback(ConfigFactory.parseResources("reference.conf").getConfig("eclair"))
    val tempConfParentDir = new File("temp-test.conf")

    val keyManager = new LocalKeyManager(seed = randomKey.toBin, chainHash = Block.TestnetGenesisBlock.hash)

    // try to create a NodeParams instance with a conf that contains an illegal alias
    val nodeParamsAttempt = Try(NodeParams.makeNodeParams(tempConfParentDir, conf, keyManager))
    assert(nodeParamsAttempt.isFailure && nodeParamsAttempt.failed.get.getMessage.contains("alias, too long"))

    // destroy conf files after the test
    Files.walkFileTree(tempConfParentDir.toPath, new SimpleFileVisitor[Path]() {
      override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
        Files.deleteIfExists(file)
        FileVisitResult.CONTINUE
      }
    })

    tempConfParentDir.listFiles.foreach(_.delete())
    tempConfParentDir.deleteOnExit()
  }

}

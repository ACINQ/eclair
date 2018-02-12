package fr.acinq.eclair.router

import java.util

import me.lemire.integercompression._
import me.lemire.integercompression.differential._
import org.scalatest.FunSuite

import scala.io.Source

class CompressShortIds extends FunSuite {
  val shortIds = {
    // list of all short channel ids n testnet on Feb.9 2017
    val stream = classOf[CompressShortIds].getResourceAsStream("/shortIds.txt")
    val ids = Source.fromInputStream(stream).getLines().toList.map(_.toLong)
    stream.close()
    ids
  }

  val splits = shortIds.map(fr.acinq.eclair.fromShortId)
  val heights = splits.map(_._1).toArray
  val txIndexes = splits.map(_._2).toArray
  val outputIndexes = splits.map(_._3).toArray

  test("compress short ids with IntegratedIntCompressor") {
    println("using IntegratedIntCompressor")
    val iic = new IntegratedIntCompressor()
    val compressed = (
      iic.compress(heights),
      iic.compress(txIndexes),
      iic.compress(outputIndexes)
    )
    println(s"uncompressed size: ${8 * shortIds.length} bytes")
    println(s"compressed size: heights ${4 * compressed._1.length} bytes")
    println(s"compressed size: tx indexes ${4 * compressed._2.length} bytes")
    println(s"compressed size: output indexes ${4 * compressed._3.length} bytes")
  }

  test("compress short ids with VariableByte and FPOR") {
    println("using VariableByte and FPOR")
    val codec = new Composition(
      new VariableByte(),
      new FastPFOR()
    )

    def compress(codec: IntegerCODEC)(input: Array[Int]) : Array[Int] = {
      val output = new Array[Int](input.length)
      val outputOffset = new IntWrapper(0)
      codec.compress(input, new IntWrapper(0), input.length, output, outputOffset)
      util.Arrays.copyOf(output, outputOffset.intValue())
    }

    val compressed = (
      compress(codec)(heights),
      compress(codec)(txIndexes),
      compress(codec)(outputIndexes)
    )
    println(s"uncompressed size: ${8 * shortIds.length} bytes")
    println(s"compressed size: heights ${4 * compressed._1.length} bytes")
    println(s"compressed size: tx indexes ${4 * compressed._2.length} bytes")
    println(s"compressed size: output indexes ${4 * compressed._3.length} bytes")
  }
}

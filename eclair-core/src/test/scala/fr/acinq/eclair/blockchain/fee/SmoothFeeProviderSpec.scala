package fr.acinq.eclair.blockchain.fee

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class SmoothFeeProviderSpec extends FunSuite {
  test("smooth fee rates") {
    val provider = new FeeProvider {
      val rates = Array(
        FeeratesPerKB(100, 200, 300, 400, 500, 600),
        FeeratesPerKB(200, 300, 400, 500, 600, 700),
        FeeratesPerKB(300, 400, 500, 600, 700, 800),
        FeeratesPerKB(300, 400, 500, 600, 700, 800),
        FeeratesPerKB(300, 400, 500, 600, 700, 800)
      )
      var index = 0

      override def getFeerates: Future[FeeratesPerKB] = {
        val rate = rates(index)
        index = (index + 1) % rates.length
        Future.successful(rate)
      }
    }

    val smoothProvider = new SmoothFeeProvider(provider, windowSize = 3)
    val f = for {
      rate1 <- smoothProvider.getFeerates
      rate2 <- smoothProvider.getFeerates
      rate3 <- smoothProvider.getFeerates
      rate4 <- smoothProvider.getFeerates
      rate5 <- smoothProvider.getFeerates
      rate6 <- smoothProvider.getFeerates
    } yield (rate1, rate2, rate3, rate4, rate5, rate6)

    val (rate1, rate2, rate3, rate4, rate5, rate6) = Await.result(f, 5 seconds)
    assert(rate1 == FeeratesPerKB(100, 200, 300, 400, 500, 600))
    assert(rate5 == FeeratesPerKB(300, 400, 500, 600, 700, 800))
  }
}

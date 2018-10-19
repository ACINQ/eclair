package fr.acinq.eclair.blockchain.fee

import org.scalatest.FunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class SmoothFeeProviderSpec extends FunSuite {
  test("smooth fee rates") {
    val rates = Array(
      FeeratesPerKB(100, 200, 300, 400, 500, 600),
      FeeratesPerKB(200, 300, 400, 500, 600, 700),
      FeeratesPerKB(300, 400, 500, 600, 700, 800),
      FeeratesPerKB(300, 400, 500, 600, 700, 800),
      FeeratesPerKB(300, 400, 500, 600, 700, 800)
    )
    val provider = new FeeProvider {
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
    } yield (rate1, rate2, rate3, rate4, rate5)

    val (rate1, rate2, rate3, rate4, rate5) = Await.result(f, 5 seconds)
    assert(rate1 == rates(0))
    assert(rate2 == SmoothFeeProvider.smooth(Seq(rates(0), rates(1))))
    assert(rate3 == SmoothFeeProvider.smooth(Seq(rates(0), rates(1), rates(2))))
    assert(rate3 ==  FeeratesPerKB(200, 300, 400, 500, 600, 700))
    assert(rate4 == SmoothFeeProvider.smooth(Seq(rates(1), rates(2), rates(3))))
    assert(rate5 == rates(4)) // since the last 3 values are the same
  }
}

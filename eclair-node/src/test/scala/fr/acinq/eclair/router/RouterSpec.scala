package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouterSpec extends BaseRouterSpec {

  test("route not found (unreachable target)") { case (router, watcher) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, f))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "route not found")
  }

  test("route not found (non-existing source)") { case (router, watcher) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomPubkey, f))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "graph must contain the source vertex")
  }

  test("route not found (non-existing target)") { case (router, watcher) =>
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomPubkey))
    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "graph must contain the sink vertex")
  }

  test("route found") { case (router, watcher) =>
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a.toBin :: b.toBin :: c.toBin :: Nil)
    assert(res.hops.last.nextNodeId === d.toBin)
  }

}

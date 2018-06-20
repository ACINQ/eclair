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

package fr.acinq.eclair

import akka.actor.{ActorNotFound, ActorSystem, PoisonPill}
import akka.testkit.TestKit
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.db.sqlite._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, fixture}

import scala.concurrent.Await

/**
  * This base class kills all actor between each tests.
  * Created by PM on 06/09/2016.
  */
abstract class TestkitBaseClass extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike
  with BeforeAndAfterEach with BeforeAndAfterAll with Logging {

  //val dbConfig = TestConstants.dbConfig
  /*  lazy val aliceDbConfig = TestConstants.Alice.aliceDbConfig
    lazy val bobDbConfig = TestConstants.Bob.bobDbConfig
    private lazy val aliceDbs = List(
      new SqliteChannelsDb(aliceDbConfig),
      new SqlitePeersDb(aliceDbConfig),
      new SqliteNetworkDb(aliceDbConfig),
      new SqlitePendingRelayDb(aliceDbConfig),
      new SqlitePaymentsDb(aliceDbConfig)
    )*/

/*  private lazy val bobDbs = List(
    new SqliteChannelsDb(bobDbConfig),
    new SqlitePeersDb(bobDbConfig),
    new SqliteNetworkDb(bobDbConfig),
    new SqlitePendingRelayDb(bobDbConfig),
    new SqlitePaymentsDb(bobDbConfig)
  )*/

  //private lazy val dbs = aliceDbs ++ bobDbs

  override def beforeAll(): Unit =  {

    Globals.blockCount.set(400000)
    Globals.feeratesPerKw.set(FeeratesPerKw.single(TestConstants.feeratePerKw))
/*    dbs.foreach { db =>
      db.createTables
    }*/
  }

  override def afterEach(): Unit = {
    system.actorSelection(system / "*") ! PoisonPill
    intercept[ActorNotFound] {
      import scala.concurrent.duration._
      Await.result(system.actorSelection(system / "*").resolveOne(42 days), 42 days)
    }
  }

  override def afterAll(): Unit = {
    //dbs.foreach(_.dropTables)
    TestKit.shutdownActorSystem(system)
    Globals.feeratesPerKw.set(FeeratesPerKw.single(1))
  }

}

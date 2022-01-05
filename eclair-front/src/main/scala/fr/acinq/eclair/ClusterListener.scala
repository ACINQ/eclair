/*
 * Copyright 2020 ACINQ SAS
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

import akka.Done
import akka.actor.{Actor, ActorLogging, Address}
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberDowned, MemberLeft, MemberUp, UnreachableMember}
import akka.cluster.{Cluster, Member}

import scala.concurrent.Promise

class ClusterListener(frontJoinedCluster: Promise[Done], backendAddressFound: Promise[Address]) extends Actor with ActorLogging {

  val cluster = Cluster(context.system)
  cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp], classOf[MemberLeft], classOf[MemberDowned], classOf[UnreachableMember])
  val BackendRole = "backend"

  override def receive: Receive = {
    case MemberUp(member) =>
      if (member.roles.contains(BackendRole)) {
        log.info(s"found backend=$member")
        backendAddressFound.success(member.address)
      } else if (member == cluster.selfMember) {
        log.info("we have joined the cluster")
        frontJoinedCluster.success(Done)
      }
    case MemberLeft(member) => maybeShutdown(member)
    case MemberDowned(member) => maybeShutdown(member)
    case UnreachableMember(member) => maybeShutdown(member)
  }

  private def maybeShutdown(member: Member): Unit = {
    if (member.roles.contains(BackendRole)) {
      log.info(s"backend is down, stopping...")
      sys.exit(0)
    }
  }

}

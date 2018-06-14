/*
 * Copyright 2017 Mateusz Bilski
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

package pl.immutables.akka.reasonable.downing

import akka.actor.Address
import akka.cluster.Cluster
import akka.cluster.MemberStatus.Up
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.remote.testkit.MultiNodeSpecCallbacks
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.duration._

object StaticQuorumByRolesSpecConfig extends MultiNodeConfig {
  // first free nodes are seed
  val first  = role("first")
  val second = role("second")
  val third  = role("third")
  // just workers
  val fourth  = role("fourth")
  val fifth  = role("fifth")

  commonConfig(ConfigFactory.parseString(
    """
      | akka.log-dead-letters = off
      | akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      | akka.cluster.auto-join = off
      | akka.reasonable.downing.stable-after = 5 seconds
      | akka.reasonable.downing.quorum-size = 2
      | akka.reasonable.downing.quorum-roles = ["seed"]
      | akka.cluster.downing-provider-class = "pl.immutables.akka.reasonable.downing.StaticQuorumDowningProvider"
      | akka.cluster.run-coordinated-shutdown-when-down = off
      | akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      | akka.coordinated-shutdown.terminate-actor-system = off
      | akka.test.single-expect-default = 5s
    """.stripMargin))

  nodeConfig(first, second, third)(ConfigFactory.parseString(
    """
      |akka.cluster.roles = ["seed"]
    """.stripMargin
  ))

  nodeConfig(fourth, fifth)(ConfigFactory.parseString(
    """
      |akka.cluster.roles = ["worker"]
    """.stripMargin
  ))

  testTransport(on = true)
}

class StaticQuorumByRolesSpecMultiJvmNode1 extends StaticQuorumByRolesSpec
class StaticQuorumByRolesSpecMultiJvmNode2 extends StaticQuorumByRolesSpec
class StaticQuorumByRolesSpecMultiJvmNode3 extends StaticQuorumByRolesSpec
class StaticQuorumByRolesSpecMultiJvmNode4 extends StaticQuorumByRolesSpec
class StaticQuorumByRolesSpecMultiJvmNode5 extends StaticQuorumByRolesSpec

class StaticQuorumByRolesSpec extends MultiNodeSpec(StaticQuorumByRolesSpecConfig)
  with STMultiNodeSpec with ImplicitSender {

  import StaticQuorumByRolesSpecConfig._

  val firstAddress  = node(first).address
  val secondAddress = node(second).address
  val thirdAddress  = node(third).address
  val fourthAddress  = node(fourth).address
  val fifthAddress  = node(fifth).address

  def initialParticipants = roles.size

  "StaticQuorumDowning" must {
    "start first node" in {
      runOn(first) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress)
      }
      enterBarrier("first started")
    }

    "start second node" in {
      runOn(second) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress)
      }
      enterBarrier("second started")
    }

    "start third node" in {
      runOn(third) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress, thirdAddress)
      }
      enterBarrier("third started")
    }

    "start fourth node" in {
      runOn(fourth) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress, thirdAddress, fourthAddress)
      }
      enterBarrier("fourth started")
    }

    "start fifth node" in {
      runOn(fifth) {
        Cluster(system) join firstAddress
        verifyNodesAreUp(firstAddress, secondAddress, thirdAddress, fourthAddress, fifthAddress)
      }
      enterBarrier("fifth started")
    }

    "split cluster in two parts" in within(30.seconds) {
      // first part is: [seed(1), seed(2)]
      // second part is: [seed(3), worker(4), worker(5)]
      runOn(first) {
        testConductor.blackhole(first, third, Direction.Both).await
        testConductor.blackhole(first, fourth, Direction.Both).await
        testConductor.blackhole(first, fifth, Direction.Both).await

        testConductor.blackhole(second, third, Direction.Both).await
        testConductor.blackhole(second, fourth, Direction.Both).await
        testConductor.blackhole(second, fifth, Direction.Both).await
      }
      enterBarrier("network partition")

      runOn(first, second) {
        verifyNodesAreUp(firstAddress, secondAddress)
        verifyNodesAreUnreachable(thirdAddress, fourthAddress, fifthAddress)
      }
      enterBarrier("second part of the cluster is unreachable")

      runOn(first) {
        awaitCond(Cluster(system).state.unreachable.isEmpty)
        awaitCond(Cluster(system).state.members.size == 2)
        testConductor.getNodes.await.size == 2
      }
      enterBarrier("second part is downed")
    }

    "down entire cluster" in within(45.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
      }
      enterBarrier("split brain")

      runOn(first) {
        verifyNodesAreUnreachable(secondAddress)
        testConductor.getNodes.await.isEmpty
      }
      enterBarrier("first and second nodes are downed")
    }
  }

  def verifyNodesAreUp(addresses: Address*) = addresses.foreach(address =>
    awaitCond(Cluster(system).state.members.exists(m => m.address == address && m.status == Up))
  )

  def verifyNodesAreUnreachable(addresses: Address*) = addresses.foreach(address =>
    awaitCond(Cluster(system).state.unreachable.exists(_.address == address))
  )
}

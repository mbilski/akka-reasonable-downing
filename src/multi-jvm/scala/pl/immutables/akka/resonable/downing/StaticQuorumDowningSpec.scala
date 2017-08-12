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

package pl.immutables.akka.resonable.downing

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

trait STMultiNodeSpec extends MultiNodeSpecCallbacks
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()
  override def afterAll() = multiNodeSpecAfterAll()
}

object StaticQuorumDowningSpecConfig extends MultiNodeConfig {
  val first  = role("first")
  val second = role("second")
  val third  = role("third")

  commonConfig(ConfigFactory.parseString(
    """
      | akka.log-dead-letters = off
      | akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      | akka.cluster.auto-join = off
      | akka.resonable.downing.stable-after = 5 seconds
      | akka.resonable.downing.quorum-size = 2
      | akka.cluster.downing-provider-class = "pl.immutables.akka.resonable.downing.StaticQuorumDowningProvider"
      | akka.cluster.run-coordinated-shutdown-when-down = off
      | akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      | akka.coordinated-shutdown.terminate-actor-system = off
    """.stripMargin))

  testTransport(on = true)
}

class StaticQuorumDowningSpecMultiJvmNode1 extends StaticQuorumDowningSpec
class StaticQuorumDowningSpecMultiJvmNode2 extends StaticQuorumDowningSpec
class StaticQuorumDowningSpecMultiJvmNode3 extends StaticQuorumDowningSpec

class StaticQuorumDowningSpec extends MultiNodeSpec(StaticQuorumDowningSpecConfig)
  with STMultiNodeSpec with ImplicitSender {

  import StaticQuorumDowningSpecConfig._

  val firstAddress  = node(first).address
  val secondAddress = node(second).address
  val thirdAddress  = node(third).address

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

    "down second node" in within(30.seconds) {
      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
        testConductor.blackhole(third, second, Direction.Both).await
      }
      enterBarrier("network partition")

      runOn(first, third) {
        verifyNodesAreUp(firstAddress, thirdAddress)
        verifyNodesAreUnreachable(secondAddress)
      }
      enterBarrier("second unreachable")

      runOn(first) {
        awaitCond(Cluster(system).state.unreachable.isEmpty)
        awaitCond(Cluster(system).state.members.size == 2)
        testConductor.getNodes.await.size == 2
      }
      enterBarrier("second downed")
    }

    "down entire cluster" in within(30.seconds) {
      runOn(first) {
        testConductor.blackhole(first, third, Direction.Both).await
      }
      enterBarrier("split brain")

      runOn(first) {
        verifyNodesAreUnreachable(thirdAddress)
        testConductor.getNodes.await.isEmpty
      }
      enterBarrier("first and third downed")
    }
  }

  def verifyNodesAreUp(addresses: Address*) = addresses.foreach(address =>
    awaitCond(Cluster(system).state.members.exists(m => m.address == address && m.status == Up))
  )

  def verifyNodesAreUnreachable(addresses: Address*) = addresses.foreach(address =>
    awaitCond(Cluster(system).state.unreachable.exists(_.address == address))
  )
}

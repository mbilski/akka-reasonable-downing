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

import akka.actor.{ Actor, ActorLogging, ActorSystem, Cancellable, Props }
import akka.cluster.ClusterEvent._
import akka.cluster.{ Cluster, DowningProvider, MemberStatus }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

class StaticQuorumDowningProvider(system: ActorSystem) extends DowningProvider {
  import scala.concurrent.ExecutionContext.Implicits.global

  val settings = StaticQuorumDowningSettings(system.settings.config)

  override def downRemovalMargin: FiniteDuration = settings.stableAfter

  override def downingActorProps: Option[Props] =
    Some(StaticQuorumDowning.props(Cluster(system), settings))
}

object StaticQuorumDowning {
  case object QuorumCheck

  def props(cluster: Cluster, settings: StaticQuorumDowningSettings)(
      implicit ex: ExecutionContext
  ) = Props(new StaticQuorumDowning(cluster, settings))
}

case class StaticQuorumDowningSettings(quorum: Int, stableAfter: FiniteDuration)

object StaticQuorumDowningSettings {
  def apply(conf: Config): StaticQuorumDowningSettings = StaticQuorumDowningSettings(
    conf.getInt("akka.resonable.downing.quorum-size"),
    FiniteDuration(conf.getDuration("akka.resonable.downing.stable-after").toMillis,
                   TimeUnit.MILLISECONDS)
  )
}

class StaticQuorumDowning(cluster: Cluster,
                          settings: StaticQuorumDowningSettings)(implicit ex: ExecutionContext)
    extends Actor
    with ActorLogging {

  import StaticQuorumDowning._
  log.info("Starting StaticQuorumDowning [{}]", settings)

  var check: Option[Cancellable] = None

  override def preStart(): Unit =
    cluster.subscribe(self,
                      initialStateMode = InitialStateAsEvents,
                      classOf[MemberEvent],
                      classOf[UnreachableMember])

  override def postStop(): Unit = cluster.unsubscribe(self)

  def scheduleQuorumCheck(): Unit = {
    check.foreach(_.cancel())
    check = Some(context.system.scheduler.scheduleOnce(settings.stableAfter, self, QuorumCheck))
  }

  def checkIfClusterStarted(): Unit =
    if (nodesOf(MemberStatus.Up).size >= settings.quorum) {
      log.debug("Cluster reached minimal number of members.")
      context.become(startedCluster)
    } else {
      log.debug("Waiting for cluster to reach minimal number of members.")
    }

  def checkQuorum(): Unit =
    if (unreachable.size >= settings.quorum) {
      log.warning("Downing reachable nodes because of {} unreachable nodes [{}]",
                  settings.quorum,
                  cluster.state)
      reachable.map(_.address).foreach(cluster.down)
    } else if (reachable.size < settings.quorum) {
      log.warning("Downing reachable nodes because of too small cluster {} [{}]",
                  reachable,
                  cluster.state)
      reachable.map(_.address).foreach(cluster.down)
    } else if (cluster.state.unreachable.nonEmpty && isLeader) {
      log.warning("Downing unreachable nodes {} [{}]", unreachable, cluster.state)
      unreachable.map(_.address).foreach(cluster.down)
    }

  def startingCluster: Receive = {
    case MemberUp(_) =>
      checkIfClusterStarted()

    case _ => // ignore
  }

  def startedCluster: Receive = {
    case MemberUp(_) =>
      scheduleQuorumCheck()

    case MemberRemoved(_, _) =>
      scheduleQuorumCheck()

    case UnreachableMember(_) =>
      scheduleQuorumCheck()

    case QuorumCheck =>
      checkQuorum()

    case _ => // ignore
  }

  def weaklyUp    = nodesOf(MemberStatus.weaklyUp)
  def unreachable = cluster.state.unreachable
  def reachable   = cluster.state.members.diff(unreachable).diff(weaklyUp)

  def isLeader                      = cluster.state.leader.contains(cluster.selfUniqueAddress.address)
  def nodesOf(status: MemberStatus) = cluster.state.members.filter(_.status == status)

  override def receive: Receive = startingCluster
}

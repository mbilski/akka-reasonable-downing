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

import akka.actor.{ Actor, ActorLogging, ActorSystem, Cancellable, Props }
import akka.cluster.ClusterEvent._
import akka.cluster.{ Cluster, DowningProvider, Member, MemberStatus }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._
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
  case object MemberCheck

  def props(cluster: Cluster, settings: StaticQuorumDowningSettings)(
      implicit ex: ExecutionContext
  ) = Props(new StaticQuorumDowning(cluster, settings))
}

case class StaticQuorumDowningSettings(quorum: Int, stableAfter: FiniteDuration, roles: Seq[String])

object StaticQuorumDowningSettings {
  def apply(conf: Config): StaticQuorumDowningSettings = StaticQuorumDowningSettings(
    quorum = conf.getInt("akka.reasonable.downing.quorum-size"),
    stableAfter = FiniteDuration(conf.getDuration("akka.reasonable.downing.stable-after").toMillis,
                                 TimeUnit.MILLISECONDS),
    roles =
      if (conf.hasPath("akka.reasonable.downing.quorum-roles"))
        conf.getStringList("akka.reasonable.downing.quorum-roles").asScala.toList
      else Nil
  )
}

class StaticQuorumDowning(cluster: Cluster, settings: StaticQuorumDowningSettings)(
    implicit ex: ExecutionContext
) extends Actor
    with ActorLogging {

  import StaticQuorumDowning._
  log.info("Starting StaticQuorumDowning [{}]", settings)

  var quorumCheck: Option[Cancellable] = None

  var memberCheck: Option[Cancellable] =
    Some(
      context.system.scheduler
        .scheduleWithFixedDelay(settings.stableAfter, settings.stableAfter, self, MemberCheck)
    )

  val quorumRoles = settings.roles.toSet

  override def preStart(): Unit =
    cluster.subscribe(self,
                      initialStateMode = InitialStateAsEvents,
                      classOf[MemberEvent],
                      classOf[UnreachableMember])

  override def postStop(): Unit = cluster.unsubscribe(self)

  def scheduleQuorumCheck(): Unit = {
    quorumCheck.foreach(_.cancel())
    quorumCheck = Some(
      context.system.scheduler.scheduleOnce(settings.stableAfter, self, QuorumCheck)
    )
  }

  def suitable(m: Member) = quorumRoles.isEmpty || quorumRoles.intersect(m.roles).nonEmpty

  def checkIfClusterStarted(): Unit =
    if (nodesOf(MemberStatus.Up).count(suitable) >= settings.quorum) {
      log.debug("Cluster reached minimal number of members.")
      memberCheck.map(_.cancel())
      memberCheck = None
      context.become(startedCluster)
    } else {
      log.debug("Waiting for cluster to reach minimal number of members.")
    }

  def checkQuorum(): Unit =
    if (unreachable.count(suitable) >= settings.quorum) {
      log.warning(
        "Downing reachable nodes because of {} unreachable nodes with roles [{}] [state={}]",
        settings.quorum,
        quorumRoles.mkString(", "),
        cluster.state
      )
      reachable.map(_.address).foreach(cluster.down)
    } else if (reachable.count(suitable) < settings.quorum) {
      log.warning("Downing reachable nodes because of too small cluster [state={}]", cluster.state)
      reachable.map(_.address).foreach(cluster.down)
    } else if (cluster.state.unreachable.nonEmpty) {
      if (isLeader) {
        log.warning("Downing unreachable nodes [state={}]", cluster.state)
        unreachable.map(_.address).foreach(cluster.down)
      } else if (isLeaderUp) {
        log.debug(
          "There are unreachable nodes but this node is not a leader. Doing nothing. [state={}]",
          cluster.state
        )
      } else {
        log.warning(
          "There are unreachable nodes and there is no leader. Downing unreachable nodes. [state={}]",
          cluster.state
        )
        unreachable.map(_.address).foreach(cluster.down)
      }
    } else {
      log.debug("Cluster is in a valid state [state={}]", cluster.state)
    }

  def startingCluster: Receive = {
    case MemberUp(_) =>
      checkIfClusterStarted()

    case MemberCheck =>
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
  def isLeader    = cluster.state.leader.contains(cluster.selfUniqueAddress.address)
  def isLeaderUp  = reachable.map(_.address).intersect(cluster.state.leader.toSet).nonEmpty

  def nodesOf(status: MemberStatus) = cluster.state.members.filter(_.status == status)

  override def receive: Receive = startingCluster
}

package com.twitter.finagle.loadbalancer.aperture

import com.twitter.finagle.Status
import com.twitter.finagle.loadbalancer.p2c.P2CPick
import com.twitter.logging.Level
import scala.collection.immutable.VectorBuilder
import scala.collection.mutable.ListBuffer

private object RandomAperture {
  private def nodeToken[Req, Rep](node: ApertureNode[Req, Rep]): Int = node.token
  private def nodeOpen[Req, Rep](node: ApertureNode[Req, Rep]): Boolean =
    node.status == Status.Open
  private def nodeBusy[Req, Rep](node: ApertureNode[Req, Rep]): Boolean =
    node.status == Status.Busy

  /**
   * Returns a new vector which is ordered by a node's status. Note, it is
   * important that this is a stable sort since we care about the source order
   * of `vec` to eliminate any unnecessary resource churn.
   */
  private def statusOrder[Req, Rep, Node <: ApertureNode[Req, Rep]](
    vec: Vector[Node]
  ): Vector[Node] = {
    val resultNodes = new VectorBuilder[Node]
    val busyNodes = new ListBuffer[Node]
    val closedNodes = new ListBuffer[Node]

    val iter = vec.iterator
    while (iter.hasNext) {
      val node = iter.next()
      node.status match {
        case Status.Open => resultNodes += node
        case Status.Busy => busyNodes += node
        case Status.Closed => closedNodes += node
      }
    }

    resultNodes ++= busyNodes ++= closedNodes
    resultNodes.result
  }
}

/**
 * A distributor which uses P2C to select nodes from within a window ("aperture").
 * The `vector` is shuffled randomly to ensure that clients talking to the same
 * set of nodes don't concentrate load on the same set of servers. However, there is
 * a known limitation with the random shuffle since servers still have a well
 * understood probability of being selected as part of an aperture (i.e. they
 * follow a binomial distribution).
 *
 * @param vector The source vector received from a call to `rebuild`.
 * @param initAperture The initial aperture to use.
 */
private final class RandomAperture[Req, Rep, NodeT <: ApertureNode[Req, Rep]](
  aperture: Aperture[Req, Rep] { type Node = NodeT },
  vector: Vector[NodeT],
  initAperture: Int)
    extends BaseDist[Req, Rep, NodeT](aperture, vector, initAperture)
    with P2CPick[NodeT] {
  require(vector.nonEmpty, "vector must be non empty")
  import RandomAperture._

  def dapertureActive: Boolean = aperture.dapertureActive

  def eagerConnections: Boolean = aperture.eagerConnections

  def minAperture: Int = aperture.minAperture

  override val rng = aperture.rng
  private[this] val labelForLogging = aperture.lbl

  // Since we don't have any process coordinate, we sort the node
  // by `token` which is deterministic across rebuilds but random
  // globally, since `token` is assigned randomly per process
  // when the node is created.
  protected val vec: Vector[NodeT] = statusOrder[Req, Rep, NodeT](vector.sortBy(nodeToken))
  protected def bound: Int = logicalAperture
  protected def emptyNode: NodeT = aperture.newFailingNode

  private[this] def vecAsString: String =
    vec
      .take(logicalAperture)
      .map(_.factory.address)
      .mkString("[", ", ", "]")

  if (aperture.rebuildLog.isLoggable(Level.DEBUG)) {
    aperture.rebuildLog.debug(s"[RandomAperture.rebuild $labelForLogging] nodes=$vecAsString")
  }

  def indices: Set[Int] = (0 until logicalAperture).toSet

  // we iterate over the entire set as RandomAperture has the ability for
  // the nodes within its logical aperture to change dynamically. `needsRebuild`
  // captures some of this behavior automatically. However, a circuit breaker sitting
  // above this balancer using `status` as a health check can shadow `needsRebuild`
  // that's checked in `Balancer.apply`.
  def status: Status = {
    var i = 0
    var status: Status = Status.Closed
    while (i < vec.length && status != Status.Open) {
      status = Status.best(status, vec(i).factory.status)

      i += 1
    }

    status
  }

  // To reduce the amount of rebuilds needed, we rely on the probabilistic
  // nature of p2c pick. That is, we know that only when a significant
  // portion of the underlying vector is unavailable will we return an
  // unavailable node to the layer above and trigger a rebuild. We do however
  // want to return to our "stable" ordering as soon as we notice that a
  // previously busy node is now available.
  private[this] val busy = vector.filter(nodeBusy)
  def needsRebuild: Boolean = busy.exists(nodeOpen)

  def additionalMetadata: Map[String, Any] = Map("nodes" -> vecAsString)
}

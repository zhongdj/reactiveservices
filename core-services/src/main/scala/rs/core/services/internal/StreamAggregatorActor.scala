package rs.core.services.internal

import java.util

import akka.actor.{ActorRef, Props}
import rs.core.actors.{ActorWithComposableBehavior, BaseActorSysevents}
import rs.core.services.Messages._
import rs.core.services.internal.NodeLocalServiceStreamEndpoint._
import rs.core.services.internal.StreamAggregatorActor.ServiceLocationChanged
import rs.core.stream.StreamState
import rs.core.tools.NowProvider
import rs.core.{ServiceKey, Subject}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps


object StreamAggregatorActor {

  def props(consumerId: String) = Props(classOf[StreamAggregatorActor], consumerId)

  case class ServiceLocationChanged(serviceKey: ServiceKey, location: Option[ActorRef])

}


trait StreamAggregatorActorSysevents extends BaseActorSysevents {

  val SubjectUpdateReceived = "SubjectUpdateReceived".trace
  val DownstreamConsumer = "DownstreamConsumer".trace
  val SentDownstream = "SentDownstream".trace
  val ServiceLocationUpdated = "ServiceLocationUpdated".trace

  override def componentId: String = "StreamAggregator"
}

final class StreamAggregatorActor(consumerId: String)
  extends ActorWithComposableBehavior
  with DemandProducerContract with StreamDemandBinding with ConsumerDemandTracker with StreamAggregatorActorSysevents {


  private val streamToBucket: mutable.Map[Subject, Bucket] = mutable.HashMap()
  private val priorityKeysToBuckets: mutable.Map[Option[String], PriorityBucketGroup] = mutable.HashMap()
  private val priorityGroups: util.ArrayList[PriorityBucketGroup] = new util.ArrayList[PriorityBucketGroup]()
  private val canUpdate = () => hasDemand && hasTarget

  private var lastDemandRequestor: Option[ActorRef] = None
  private var pendingMessages: List[Any] = List.empty
  private var pendingPublisherIdx = 0
  private var serviceLocations: Map[ServiceKey, Option[ActorRef]] = Map.empty

  override def commonFields: Seq[(Symbol, Any)] = super.commonFields ++ Seq('consumer -> consumerId)

  @throws[Exception](classOf[Exception]) override
  def preStart(): Unit = {
    super.preStart()
    scheduleNextCheck()
  }

  def activeSubjects = streamToBucket.keys

  def invalidRequest(subj: Subject) = {
    pendingMessages = pendingMessages :+ InvalidRequest(subj)
    processPendingMessages()
  }

  def serviceAvailable(service: ServiceKey) = pendingMessages = pendingMessages filter {
    case ServiceNotAvailable(key) => key != service
    case _ => true
  }

  onMessage {
    case StreamStateUpdate(key, tran) =>
      onUpdate(key, tran)
    case SendPending =>
      publishPending()
      scheduleNextCheck()
  }


  private def onUpdate(key: Subject, tran: StreamState): Unit = SubjectUpdateReceived { ctx =>
    ctx +('subj -> key, 'payload -> tran)
    upstreamDemandFulfilled(sender(), 1)
    streamToBucket get key foreach { b =>
      b.onNewState(canUpdate, tran, send)
    }
  }

  private def publishPending(): Unit = if (priorityGroups.size() > 0) {
    processPendingMessages()
    val cycles = priorityGroups.size()
    var cnt = 0
    while (cnt < cycles && hasDemand) {
      if (pendingPublisherIdx < 0 || pendingPublisherIdx >= priorityGroups.size()) pendingPublisherIdx = 0
      priorityGroups get pendingPublisherIdx publishPending(canUpdate, send)
      pendingPublisherIdx += 1
      cnt += 1
    }
  }

  def serviceUnavailable(service: ServiceKey) = if (!pendingMessages.exists {
    case ServiceNotAvailable(key) => key == service
    case _ => false
  }) pendingMessages = pendingMessages :+ ServiceNotAvailable(service)

  def subjectClosed(subj: Subject) = {
    pendingMessages = pendingMessages :+ SubscriptionClosed(subj)
    processPendingMessages()
  }

  def remove(subj: Subject) = {
    streamToBucket get subj foreach closeBucket
  }

  def add(subj: Subject, priorityKey: Option[String], aggregationIntervalMs: Int) = {
    streamToBucket get subj foreach closeBucket
    newBucket(subj, priorityKey, aggregationIntervalMs)
  }

  override def onConsumerDemand(sender: ActorRef, demand: Long): Unit = {
    if (!lastDemandRequestor.contains(sender)) {
      DownstreamConsumer('ref -> sender)
      lastDemandRequestor = Some(sender)
    }
    addConsumerDemand(demand)
    publishPending()
  }

  @throws[Exception](classOf[Exception]) override
  def postStop(): Unit = {
    serviceLocations.values.flatten foreach { loc =>
      loc ! CloseAllLocalStreams
    }
    super.postStop()
  }


  private def hasTarget = lastDemandRequestor isDefined

  private def scheduleNextCheck() = scheduleOnce(200 millis, SendPending)

  @tailrec private def processPendingMessages(): Unit = {
    if (pendingMessages.nonEmpty && canUpdate()) {
      send(pendingMessages.head)
      pendingMessages = pendingMessages.tail
      processPendingMessages()
    }
  }

  private def remove(pg: PriorityBucketGroup) = {
    priorityKeysToBuckets -= pg.priorityKey
    priorityGroups remove pg
  }


  private def closeBucket(bucket: Bucket): Unit = {
    priorityKeysToBuckets get bucket.priorityKey foreach { pg =>
      pg.remove(bucket)
      if (pg.isEmpty) remove(pg)
    }
    streamToBucket -= bucket.subj
  }

  private def newPriorityGroup(key: Option[String]) = {
    val group = new PriorityBucketGroup(key)
    priorityKeysToBuckets += key -> group
    priorityGroups.add(group)
    util.Collections.sort(priorityGroups)
    group
  }

  private def initialiseBucket(bucket: Bucket) = {
    priorityKeysToBuckets getOrElse(bucket.priorityKey, newPriorityGroup(bucket.priorityKey)) add bucket
  }

  private def newBucket(key: Subject, priorityKey: Option[String], aggregationIntervalMs: Int): Unit = {
    val bucket = new Bucket(key, priorityKey, aggregationIntervalMs)
    streamToBucket += key -> bucket
    initialiseBucket(bucket)
  }

  private def send(msg: Any) = fulfillDownstreamDemandWith {
    lastDemandRequestor foreach {
      ref =>
        ref ! msg
        SentDownstream('ref -> ref, 'payload -> msg)
    }
  }


  private def closeLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten foreach { loc =>
      cancelDemandProducerFor(loc)
      loc ! CloseAllLocalStreams
    }

  private def openLocation(service: ServiceKey) =
    serviceLocations.get(service).flatten match {
      case Some(loc) =>
        startDemandProducerFor(loc, withAcknowledgedDelivery = false)
        serviceAvailable(service)
        loc ! OpenLocalStreamsForAll(activeSubjects.filter(_.service == service).toList)
      case None =>
        serviceUnavailable(service)
    }


  onMessage {
    case InvalidRequest(subj) => invalidRequest(subj)
    case OpenSubscription(subj, prioKey, aggr) =>
      subjectClosed(subj)
      add(subj, prioKey, aggr)
      serviceLocations.get(subj.service).flatten foreach { loc =>
        loc ! OpenLocalStreamFor(subj)
      }
    case CloseSubscription(subj) =>
      subjectClosed(subj)
      remove(subj) //TODO check that upstream stream is closed
      serviceLocations.get(subj.service).flatten foreach { loc =>
        loc ! CloseLocalStreamFor(subj)
      }

    case ServiceLocationChanged(sKey, newLoc) => switchLocation(sKey, newLoc)

  }

  private def switchLocation(service: ServiceKey, location: Option[ActorRef]): Unit = ServiceLocationUpdated { ctx =>
    closeLocation(service)
    serviceLocations += service -> location
    openLocation(service)
    ctx +('service -> service, 'ref -> location)
  }

  private case object SendPending

}


private class PriorityBucketGroup(val priorityKey: Option[String]) extends Comparable[PriorityBucketGroup] {
  private lazy val preCalculatedHashCode = priorityKey.hashCode()
  private val buckets: util.ArrayList[Bucket] = new util.ArrayList[Bucket]()
  private var idx = 0

  def publishPending(canUpdate: () => Boolean, send: (Any) => Unit) = if (buckets.size() > 0) {
    val cycles = buckets.size()
    var cnt = 0
    while (cnt < cycles && canUpdate()) {
      if (idx < 0 || idx >= buckets.size()) idx = 0
      buckets.get(idx).publishPending(canUpdate, send)
      idx += 1
      cnt += 1
    }
  }

  def add(b: Bucket) = buckets add b

  def remove(b: Bucket) = buckets remove b

  def isEmpty = buckets.isEmpty


  //noinspection ComparingUnrelatedTypes
  def canEqual(other: Any): Boolean = other.isInstanceOf[PriorityBucketGroup]

  override def equals(other: Any): Boolean = other match {
    case that: PriorityBucketGroup =>
      (that canEqual this) &&
        priorityKey == that.priorityKey
    case _ => false
  }

  override def hashCode(): Int = preCalculatedHashCode

  override def compareTo(o: PriorityBucketGroup): Int = priorityKey match {
    case x if x == o.priorityKey => 0
    case Some(x) if o.priorityKey.exists(_ < x) => 1
    case _ => -1
  }
}

private class Bucket(val subj: Subject, val priorityKey: Option[String], aggregationIntervalMs: Int) {

  import NowProvider._

  var lastUpdatePublishedAt = 0L
  private var pendingState: Option[StreamState] = None
  private var state: Option[StreamState] = None

  def publishPending(canUpdate: () => Boolean, send: Any => Unit) = {

    if (canUpdate() && pendingState.isDefined && isAggregationCriteriaMet) pendingState foreach { pending =>
      lastUpdatePublishedAt = now
      send(StreamStateUpdate(subj, pending))
      state = pendingState
      pendingState = None
    }
  }

  def onNewState(canUpdate: () => Boolean, tran: StreamState, send: Any => Unit): Unit = {
    schedule(tran)
    publishPending(canUpdate, send)
  }

  private def isAggregationCriteriaMet = aggregationIntervalMs < 1 || now - lastUpdatePublishedAt > aggregationIntervalMs

  private def schedule(tran: StreamState) = pendingState = Some(tran)

}


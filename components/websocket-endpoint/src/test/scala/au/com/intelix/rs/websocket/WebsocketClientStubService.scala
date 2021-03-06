/*
 * Copyright 2014-16 Intelix Pty Ltd
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
package au.com.intelix.rs.websocket

import akka.actor.{ActorRef, FSM, Props, Stash}
import akka.io.IO
import akka.util.{ByteIterator, ByteString}
import au.com.intelix.evt.{CommonEvt, EvtSource, InfoE}
import au.com.intelix.rs.core.Subject
import au.com.intelix.rs.core.actors.{ActorState, StatefulActor}
import au.com.intelix.rs.core.codec.binary.BinaryProtocolMessages._
import au.com.intelix.rs.core.services.StatelessServiceActor
import au.com.intelix.rs.core.services.endpoint.StreamConsumer
import au.com.intelix.rs.core.stream._
import au.com.intelix.rs.websocket.WebSocketClient.WebsocketConnection
import au.com.intelix.rs.websocket.WebsocketClientStubService.{CloseSubscriptionFromStub, OpenSubscriptionFromStub, ResetSubscriptionFromStub, SignalFromStub}
import spray.can.Http.Connect
import spray.can.server.UHttp
import spray.can.websocket.frame.{BinaryFrame, TextFrame}
import spray.can.{Http, websocket}
import spray.http.{HttpHeaders, HttpMethods, HttpRequest, HttpResponse}

import scala.annotation.tailrec
import scala.language.postfixOps


object WebsocketClientStubService {

  case class StartWebsocketClient(id: String, host: String, port: Int)

  case class OpenSubscriptionFromStub(subj: Subject, priorityKey: Option[String] = None, aggregationIntervalMs: Int = 0)

  case class CloseSubscriptionFromStub(subj: Subject)

  case class ResetSubscriptionFromStub(subj: Subject)

  case class SignalFromStub(subj: Subject, payload: Any, expireAt: Long, orderingGroup: Option[Any], correlationId: Option[Any])

}

class WebsocketClientStubService extends StatelessServiceActor {

  import WebsocketClientStubService._

  onMessage {
    case StartWebsocketClient(id, host, port) => context.actorOf(Props(classOf[WebSocketClient], id, host, port), id)
  }
}

trait Consumer
  extends StreamConsumer
    with StringStreamConsumer
    with DictionaryMapStreamConsumer
    with SetStreamConsumer
    with ListStreamConsumer

object WebSocketClient {

  object Evt {
    case object ConnectionUpgraded extends InfoE
    case object ConnectionEstablished extends InfoE
    case object ConnectionClosed extends InfoE
    case object ReceivedPing extends InfoE
    case object ReceivedServiceNotAvailable extends InfoE
    case object ReceivedInvalidRequest extends InfoE
    case object ReceivedSubscriptionClosed extends InfoE
    case object ReceivedStreamStateUpdate extends InfoE
    case object ReceivedStreamStateTransitionUpdate extends InfoE
    case object ReceivedSignalAckOk extends InfoE
    case object ReceivedSignalAckFailed extends InfoE
    case object StringUpdate extends InfoE
    case object SetUpdate extends InfoE
    case object MapUpdate extends InfoE
    case object ListUpdate extends InfoE
  }

  case class WebsocketConnection(connection: Option[ActorRef] = None)

  case object Connecting extends ActorState

  case object Established extends ActorState

}

class WebSocketClient(id: String, endpoint: String, port: Int)
  extends StatefulActor[WebsocketConnection]
    with Consumer
    with Stash {

  import WebSocketClient._

  commonEvtFields('id -> id)

  startWith(Connecting, WebsocketConnection())

  when(Connecting) {
    case Event(Http.Connected(remoteAddress, localAddress), state) =>
      val upgradePipelineStage = { response: HttpResponse =>
        response match {
          case websocket.HandshakeResponse(st) =>
            st match {
              case wsFailure: websocket.HandshakeFailure => None
              case wsContext: websocket.HandshakeContext => Some(websocket.clientPipelineStage(self, wsContext))
            }
        }
      }
      raise(Evt.ConnectionEstablished)
      sender() ! UHttp.UpgradeClient(upgradePipelineStage, upgradeRequest)
      stay()

    case Event(UHttp.Upgraded, state: WebsocketConnection) =>
      raise(Evt.ConnectionUpgraded)
      unstashAll()
      transitionTo(Established) using state.copy(connection = Some(sender()))

    case Event(Http.CommandFailed(con: Connect), state) =>
      val msg = s"failed to connect to ${con.remoteAddress}"
      raise(CommonEvt.Evt.Error, 'msg -> msg)
      stop(FSM.Failure(msg))

    case Event(_, state) =>
      stash()
      stay()
  }

  when(Established) {
    case Event(ev: Http.ConnectionClosed, state) =>
      raise(Evt.ConnectionClosed)
      stop(FSM.Normal)
    case Event(t: BinaryDialectInbound, state: WebsocketConnection) =>
      state.connection.foreach(_ ! BinaryFrame(encode(t)))
      stay()
  }

  onMessage {
    case TextFrame(bs) => throw new UnsupportedOperationException(bs.utf8String)
    case BinaryFrame(bs) => decode(bs) foreach {
      case BinaryDialectPing(pid) =>
        self ! BinaryDialectPong(pid)
        raise(Evt.ReceivedPing, 'pingId -> pid)
      case BinaryDialectSubscriptionClosed(alias) =>
        raise(Evt.ReceivedSubscriptionClosed, 'alias -> alias)
      case BinaryDialectServiceNotAvailable(service) =>
        raise(Evt.ReceivedServiceNotAvailable, 'service -> service)
      case BinaryDialectInvalidRequest(alias) =>
        raise(Evt.ReceivedInvalidRequest, 'alias -> alias)
      case BinaryDialectStreamStateUpdate(alias, state) =>
        raise(Evt.ReceivedStreamStateUpdate, 'alias -> alias, 'state -> state)
        translate(alias, state)
      case BinaryDialectStreamStateTransitionUpdate(alias, trans) =>
        raise(Evt.ReceivedStreamStateTransitionUpdate, 'alias -> alias, 'transition -> trans)
        transition(alias, trans)
      case BinaryDialectSignalAckOk(correlation, payload) =>
        raise(Evt.ReceivedSignalAckOk, 'correlation -> correlation, 'payload -> payload)
      case BinaryDialectSignalAckFailed(correlation, payload) =>
        raise(Evt.ReceivedSignalAckFailed, 'correlation -> correlation, 'payload -> payload)
    }

    case OpenSubscriptionFromStub(subj, key, aggrInt) =>
      self ! BinaryDialectOpenSubscription(aliasFor(subj), key, aggrInt)
    case CloseSubscriptionFromStub(subj) =>
      self ! BinaryDialectCloseSubscription(aliasFor(subj))
    case ResetSubscriptionFromStub(subj) =>
      self ! BinaryDialectResetSubscription(aliasFor(subj))
    case SignalFromStub(subj, payload, expireAt, group, correlation) =>
      self ! BinaryDialectSignal(subj, payload, expireAt, group, correlation)

  }

  private def transition(alias: Int, trans: StreamStateTransition) =
    aliases.find(_._2 == alias).foreach {
      case (s, _) =>
        val st = states.getOrElse(s, None)
        if (trans.applicableTo(st))
          trans.toNewStateFrom(st) match {
            case x@Some(newState) =>
              update(s, newState)
              states += s -> x
            case None => states -= s
          }
    }


  private def translate(alias: Int, state: StreamState) = {
    aliases.find(_._2 == alias).foreach {
      case (s, _) =>
        states += s -> Some(state)
        update(s, state)
    }
  }

  val connect = Http.Connect(endpoint, port, sslEncryption = false)

  val headers = List(
    HttpHeaders.Host(endpoint, port),
    HttpHeaders.Connection("Upgrade"),
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Sec-WebSocket-Version", "13"),
    HttpHeaders.RawHeader("Sec-WebSocket-Key", "x3JJHMbDL1EzLkh9GBhXDw=="),
    HttpHeaders.RawHeader("Sec-WebSocket-Extensions", "permessage-deflate"))

  val upgradeRequest: HttpRequest = HttpRequest(HttpMethods.GET, "/", headers)

  implicit val sys = context.system
  implicit val ec = context.dispatcher

  IO(UHttp) ! connect

  private var counter: Int = 0
  private var aliases: Map[Subject, Int] = Map()
  private var states: Map[Subject, Option[StreamState]] = Map()

  private def aliasFor(subj: Subject) = aliases getOrElse(subj, {
    counter += 1
    aliases += subj -> counter
    self ! BinaryDialectAlias(counter, subj)
    counter
  })


  import au.com.intelix.rs.core.codec.binary.BinaryCodec.DefaultBinaryCodecImplicits

  def decode(bs: ByteString): List[BinaryDialectOutbound] = {
    @tailrec def dec(l: List[BinaryDialectOutbound], i: ByteIterator): List[BinaryDialectOutbound] = {
      if (!i.hasNext) l else dec(l :+ DefaultBinaryCodecImplicits.clientBinaryCodec.decode(i), i)
    }

    val i = bs.iterator
    val decoded = dec(List.empty, i)
    decoded
  }

  def encode(bdi: BinaryDialectInbound): ByteString = {
    val b = ByteString.newBuilder
    DefaultBinaryCodecImplicits.clientBinaryCodec.encode(bdi, b)
    val encoded = b.result()
    encoded
  }

  onStringRecord {
    case (s, str) => raise(Evt.StringUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> str)
  }

  onSetRecord {
    case (s, set) => raise(Evt.SetUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> set.toList.map(_.toString).sorted.mkString(","))
  }

  onDictMapRecord {
    case (s, map) => raise(Evt.MapUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> map.asMap)
  }

  onListRecord {
    case (s, list) => raise(Evt.ListUpdate, 'sourceService -> s.service.id, 'topic -> s.topic.id, 'keys -> s.tags, 'value -> list.mkString(","))
  }
}



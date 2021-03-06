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

package au.com.intelix.rs.core.actors

import akka.actor.{Actor, ActorRef, FSM, Terminated}
import au.com.intelix.config.RootConfig
import au.com.intelix.evt.EvtContext
import au.com.intelix.rs.core.config.WithActorSystemConfig

trait ActorState

trait StatefulActor[T] extends FSM[ActorState, T] with BaseActor {

  import CommonActorEvt._

  private var chainedUnhandled: StateFunction = {
    case Event(Terminated(ref), _) => terminatedFuncChain.foreach(_ (ref)); stay()
  }

  final override def onMessage(f: Receive) = otherwise {
    case Event(x, _) if f.isDefinedAt(x) => f(x); stay()
  }

  final def otherwise(f: StateFunction) = {
    chainedUnhandled = f orElse chainedUnhandled
  }

  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    initialize()
  }

  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    super.preStart()
    initialize()
  }

  def transitionTo(state: ActorState) = {
    if (stateName != state) raise(Evt.StateTransition, 'to -> state, 'from -> stateName)
    goto(state)
  }

  whenUnhandled {
    case x if chainedUnhandled.isDefinedAt(x) => chainedUnhandled(x)
  }

}


/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.metrics.sender

import java.net.InetSocketAddress

import io.gatling.metrics.message.SendMetric

import scala.concurrent.duration._

import akka.io.{ IO, Tcp }

private[metrics] class TcpSender(remote: InetSocketAddress) extends MetricsSender with TcpSenderStateMachine {

  import Tcp._

  // Initial ask for a connection to IO manager
  askForConnection()

  // Wait for answer from IO manager
  startWith(WaitingForConnection, DisconnectedData(Failures(5, 5.seconds)))

  when(WaitingForConnection) {
    // Connection succeeded: proceed to running state
    case Event(_: Connected, DisconnectedData(failures)) =>
      unstashAll()
      val connection = sender()
      connection ! Register(self)
      goto(Running) using ConnectedData(connection, failures)

    // Connection failed: either stop if all retries are exhausted or retry connection
    case Event(CommandFailed(_: Connect), DisconnectedData(failures)) =>
      logger.info(s"Failed to connect to Graphite server located at: $remote")
      val newFailures = failures.newFailure

      stopIfLimitReachedOrContinueWith(newFailures) {
        scheduler.scheduleOnce(1.second)(askForConnection())
        stay() using DisconnectedData(newFailures)
      }

    case _ =>
      stash()
      stay()
  }

  when(Running) {
    // GraphiteDataWriter sent a metric, write to socket
    case Event(m: SendMetric[_], ConnectedData(connection, _)) =>
      connection ! Write(m.byteString)
      stay()

    // Connection actor failed to send metric, log it as a failure
    case Event(CommandFailed(_: Write), data: ConnectedData) =>
      val newFailures = data.failures.newFailure

      stopIfLimitReachedOrContinueWith(newFailures) {
        stay() using data.copy(failures = newFailures)
      }

    // Server quits unexpectedly, retry connection
    case Event(PeerClosed, data: ConnectedData) =>
      val newFailures = data.failures.newFailure

      stopIfLimitReachedOrContinueWith(newFailures) {
        scheduler.scheduleOnce(1.second)(askForConnection())
        goto(WaitingForConnection) using DisconnectedData(newFailures)
      }
  }

  when(RetriesExhausted) {
    case _ =>
      logger.debug("All connection/sending have been exhausted, ignore further messages")
      stay()
  }

  initialize()

  def askForConnection(): Unit =
    IO(Tcp) ! Connect(remote)

  def stopIfLimitReachedOrContinueWith(failures: Failures)(continueState: this.State) =
    if (failures.isLimitReached) goto(RetriesExhausted) using NoData
    else continueState
}

/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.eclair.payment.{LocalFailure, PaymentFailure, RemoteFailure, UnreadableRemoteFailure}
import kamon.Kamon
import kamon.tag.TagSet
import kamon.trace.Span

import scala.concurrent.{ExecutionContext, Future}

object KamonExt {

  def time[T](name: String, tags: TagSet = TagSet.Empty)(f: => T) = {
    val timer = Kamon.timer(name).withTags(tags).start()
    try {
      f
    } finally {
      timer.stop()
    }
  }

  def timeFuture[T](name: String, tags: TagSet = TagSet.Empty)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val timer = Kamon.timer(name).withTags(tags).start()
    val res = f
    res onComplete { case _ => timer.stop }
    res
  }

  /**
   * A helper function that fails a span with proper messages when dealing with payments
   */
  def failSpan(span: Span, failure: PaymentFailure) = {
    failure match {
      case LocalFailure(t) => span.fail("local failure", t)
      case RemoteFailure(_, e) => span.fail(s"remote failure: origin=${e.originNode} error=${e.failureMessage}")
      case UnreadableRemoteFailure(_) => span.fail("unreadable remote failure")
    }
  }

}

/*
 * Copyright 2014 Philip L. McMahon
 *
 * Philip L. McMahon licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package rascql.postgresql.stream

import akka.event.LoggingAdapter
import akka.stream.stage._

/**
 * A simple logging stage.
 *
 * @author Philip L. McMahon
 */
class LoggingStage[A](name: String, log: LoggingAdapter) extends PushStage[A, A] {

  override def onDownstreamFinish(ctx: Context[A]) = {
    log.debug("{} downstream finished", name)
    super.onDownstreamFinish(ctx)
  }

  override def onUpstreamFailure(cause: Throwable, ctx: Context[A]) = {
    log.debug("{} upstream failed ({})", name, cause.getMessage)
    super.onUpstreamFailure(cause, ctx)
  }

  override def onUpstreamFinish(ctx: Context[A]) = {
    log.debug("{} upstream finished", name)
    super.onUpstreamFinish(ctx)
  }

  override def onPush(elem: A, ctx: Context[A]) = {
    log.debug("{} pushing {}", name, elem)
    ctx.push(elem)
  }

}

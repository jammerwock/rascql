/*
 * Copyright 2015 Philip L. McMahon
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

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKitBase
import org.scalatest._

/**
 * Base trait for stream-based test specifications.
 *
 * @author Philip L. McMahon
 */
private[stream] trait StreamSpec extends TestKitBase with WordSpecLike with BeforeAndAfterAll {

  implicit lazy val system = ActorSystem(this.getClass.getSimpleName)

  implicit lazy val materializer = ActorMaterializer()

  override protected def afterAll() = system.shutdown()

}

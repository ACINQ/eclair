/*
 * Copyright 2020 ACINQ SAS
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

import org.scalactic.anyvals.PosInt
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FunSuite, ParallelTestExecution, Tag}

import scala.util.Try

/**
 * Created by t-bast on 10/03/2020.
 */

abstract class FuzzingBaseClass extends FunSuite with GeneratorDrivenPropertyChecks with ParallelTestExecution {

  lazy val fuzzCount = sys.props.get("fuzzFactor").flatMap(f => Try(f.toInt).toOption).flatMap(PosInt.from).getOrElse(PosInt(10))

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = fuzzCount)

}

object FuzzingBaseClass {

  object Tags {

    // Use this tag for fuzz tests to ensure they run in nightly builds with a high degree of fuzzing.
    object Fuzzy extends Tag("fuzzy")

  }

}
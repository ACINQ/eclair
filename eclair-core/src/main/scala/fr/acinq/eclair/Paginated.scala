/*
 * Copyright 2022 ACINQ SAS
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

/**
 * Created by rorp on 03/11/2022.
 */

/**
 * Simple class for pagination database and API queries.
 */
case class Paginated(count: Int, skip: Int) {
  require(count >= 0, "count must be a positive number")
  require(skip >= 0, "skip must be a positive number")
}

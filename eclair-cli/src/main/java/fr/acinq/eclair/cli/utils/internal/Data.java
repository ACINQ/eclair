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

package fr.acinq.eclair.cli.utils.internal;

/**
 * This is a backbone version of the {@link fr.acinq.eclair.channel.Data} class from eclair-core.
 * Only the fields included in this class will be retrieved from a JSON-serialized Data class.
 */
public class Data {

  public Commitments commitments;
  public String shortChannelId;

  public Data(Commitments commitments, String shortChannelId) {
    this.commitments = commitments;
    this.shortChannelId = shortChannelId;
  }
}

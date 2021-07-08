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

package fr.acinq.eclair.cli.utils.models;

import fr.acinq.eclair.cli.utils.internal.Data;

public class ChannelsRes {

  public String nodeId;
  public String channelId;
  public String state;
  public Data data;

  public String getNodeId() {
    return nodeId;
  }

  public String getShortChannelId() {
    return data.shortChannelId;
  }

  public Long getBalanceSat() {
    return new Double(Math.floor(data.commitments.localCommit.spec.toLocalMsat / 1000)).longValue();
  }
  public Long getCapacitySat() {
    return data.commitments.commitInput.amountSatoshis;
  }

  public String getChannelPoint() {
    return data.commitments.commitInput.outPoint;
  }
}

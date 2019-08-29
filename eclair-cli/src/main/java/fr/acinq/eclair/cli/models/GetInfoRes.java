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

package fr.acinq.eclair.cli.models;

import java.util.List;

public class GetInfoRes {

  public String nodeId;
  public String alias;
  public String chainHash;
  public String blockHeight;
  public List<String> publicAddresses;

  public GetInfoRes(String nodeId, String alias, String chainHash, String blockHeight, List<String> publicAddresses) {
    this.nodeId = nodeId;
    this.alias = alias;
    this.chainHash = chainHash;
    this.blockHeight = blockHeight;
    this.publicAddresses = publicAddresses;
  }
}

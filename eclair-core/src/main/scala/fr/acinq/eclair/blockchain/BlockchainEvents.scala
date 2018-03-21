/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{Block, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw

/**
  * Created by PM on 24/08/2016.
  */

trait BlockchainEvent

case class NewBlock(block: Block) extends BlockchainEvent

case class NewTransaction(tx: Transaction) extends BlockchainEvent

case class CurrentBlockCount(blockCount: Long) extends BlockchainEvent

case class CurrentFeerates(feeratesPerKw: FeeratesPerKw) extends BlockchainEvent

/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.TxId
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}

/**
 * Created by t-bast on 13/09/2024. 
 */

trait LiquidityDb {

  def addPurchase(liquidityPurchase: ChannelLiquidityPurchased): Unit

  def setConfirmed(remoteNodeId: PublicKey, txId: TxId): Unit

  def listPurchases(remoteNodeId: PublicKey): Seq[LiquidityPurchase]

}

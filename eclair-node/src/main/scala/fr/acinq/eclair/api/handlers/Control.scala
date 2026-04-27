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

package fr.acinq.eclair.api.handlers

import akka.http.scaladsl.server.Route
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.Musig2.IndividualNonce
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OutPoint, Satoshi, Transaction, TxId}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.channel.ChannelSpendSignature
import fr.acinq.eclair.channel.ChannelSpendSignature.PartialSignatureWithNonce
import fr.acinq.eclair.profit.PeerScorer

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

trait Control {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val enableFromFutureHtlc: Route = postRequest("enablefromfuturehtlc") { implicit t =>
    formFields("suspiciousNodeIds".as[List[PublicKey]](pubkeyListUnmarshaller) ?) { suspiciousNodes =>
      complete(eclairApi.enableFromFutureHtlc(suspiciousNodes.map(_.toSet).getOrElse(Set.empty)))
    }
  }

  val resetBalance: Route = postRequest("resetbalance") { implicit t =>
    complete(eclairApi.resetBalance())
  }

  val forceCloseResetFundingIndex: Route = postRequest("forcecloseresetfundingindex") { implicit t =>
    withChannelIdentifier { channel =>
      formFields("resetFundingIndex".as[Int]) {
        resetFundingIndex =>
          complete(eclairApi.forceCloseResetFundingIndex(channel, resetFundingIndex))
      }
    }
  }

  val manualWatchFundingSpent: Route = postRequest("manualwatchfundingspent") { implicit t =>
    formFields(channelIdFormParam, "tx") {
      (channelId, tx) =>
        complete(eclairApi.manualWatchFundingSpent(channelId, Transaction.read(tx)))
    }
  }

  val spendFromChannelAddressPrep: Route = postRequest("spendfromchanneladdressprep") { implicit t =>
    formFields("t".as[ByteVector32], "o".as[Int], "kp", "fi".as[Int], "address", "f".as[FeeratePerByte]) {
      (txId, outputIndex, keyPath, fundingTxIndex, address, feerate) =>
        complete(eclairApi.spendFromChannelAddressPrep(OutPoint(TxId(txId), outputIndex), KeyPath(keyPath), fundingTxIndex, address, feerate.perKw))
    }
  }

  val spendFromChannelAddress: Route = postRequest("spendfromchanneladdress") { implicit t =>
    formFields("kp", "fi".as[Int], "p".as[PublicKey], "s".as[ByteVector64], "tx") {
      (keyPath, fundingTxIndex, remoteFundingPubkey, remoteSig, unsignedTx) =>
        complete(eclairApi.spendFromChannelAddress(KeyPath(keyPath), fundingTxIndex, remoteFundingPubkey, localNonce_opt = None, ChannelSpendSignature.IndividualSignature(remoteSig), Transaction.read(unsignedTx)))
    }
  }

  val spendFromTaprootChannelAddress: Route = postRequest("spendfromtaprootchanneladdress") { implicit t =>
    formFields("kp", "fi".as[Int], "p".as[PublicKey], "localNonce".as[IndividualNonce], "remoteNonce".as[IndividualNonce], "remoteSig".as[ByteVector32], "tx".as[String]) {
      (keyPath, fundingTxIndex, remoteFundingPubkey, localNonce, remoteNonce, remoteSig, unsignedTx) =>
        complete(eclairApi.spendFromChannelAddress(KeyPath(keyPath), fundingTxIndex, remoteFundingPubkey, localNonce_opt = Some(localNonce), PartialSignatureWithNonce(remoteSig, remoteNonce), Transaction.read(unsignedTx)))
    }
  }

  val configurePeerScorer: Route = postRequest("configurepeerscorer") { implicit t =>
    formFields("autoFund".as[Boolean] ?, "autoClose".as[Boolean] ?, "autoUpdateFees".as[Boolean] ?, "addWhitelistedPeers".as[List[PublicKey]](pubkeyListUnmarshaller).?, "removeWhitelistedPeers".as[List[PublicKey]](pubkeyListUnmarshaller).?, "minFundingAmount".as[Satoshi] ?, "maxFundingAmount".as[Satoshi] ?, "maxPerPeerCapacity".as[Satoshi] ?, "maxFundingTxPerDay".as[Int] ?, "localBalanceClosingThreshold".as[Satoshi] ?, "remoteBalanceClosingThreshold".as[Satoshi] ?, "minOnChainBalance".as[Satoshi] ?, "maxFeerate".as[FeeratePerByte] ?, "reviveOldPeers".as[Boolean] ?, "fundingCooldown".as[Int] ?) {
      (autoFund_opt, autoClose_opt, autoUpdateFees_opt, addWhitelistedPeers_opt, removeWhitelistedPeers_opt, minFundingAmount_opt, maxFundingAmount_opt, maxPerPeerCapacity_opt, maxFundingTxPerDay_opt, localBalanceClosingThreshold_opt, remoteBalanceClosingThreshold_opt, minOnChainBalance_opt, maxFeerate_opt, reviveOldPeers_opt, fundingCooldown_opt) =>
        val cfg = PeerScorer.ConfigOverrides(
          autoFundOverride_opt = autoFund_opt,
          autoCloseOverride_opt = autoClose_opt,
          autoUpdateFeesOverride_opt = autoUpdateFees_opt,
          addWhiteListedPeers = addWhitelistedPeers_opt.map(_.toSet).getOrElse(Set.empty),
          removeWhiteListedPeers = removeWhitelistedPeers_opt.map(_.toSet).getOrElse(Set.empty),
          minFundingAmountOverride_opt = minFundingAmount_opt,
          maxFundingAmountOverride_opt = maxFundingAmount_opt,
          maxPerPeerCapacityOverride_opt = maxPerPeerCapacity_opt,
          maxFundingTxPerDayOverride_opt = maxFundingTxPerDay_opt,
          localBalanceClosingThresholdOverride_opt = localBalanceClosingThreshold_opt,
          remoteBalanceClosingThresholdOverride_opt = remoteBalanceClosingThreshold_opt,
          minOnChainBalanceOverride_opt = minOnChainBalance_opt,
          maxFeerateOverride_opt = maxFeerate_opt.map(_.perKw),
          reviveOldPeersOverride_opt = reviveOldPeers_opt,
          fundingCooldownOverride_opt = fundingCooldown_opt.map(hours => FiniteDuration(hours, TimeUnit.HOURS)),
        )
        complete(eclairApi.configurePeerScorer(cfg))
    }
  }

  val controlRoutes: Route = enableFromFutureHtlc ~ resetBalance ~ forceCloseResetFundingIndex ~ manualWatchFundingSpent ~ spendFromChannelAddressPrep ~ spendFromChannelAddress ~ spendFromTaprootChannelAddress ~ configurePeerScorer

}

# Eclair v0.12.0

This release adds support for creating and managing Bolt 12 offers and a new channel closing protocol (`option_simple_close`) that supports RBF.
We also add support for storing small amounts of (encrypted) data for our peers (`option_provide_storage`).

:warning: This release also starts using Java 21, which means you may need to update your runtime.
:warning: We also update our dependency on Bitcoin Core to v28.1 to benefit from package relay.

We've also made more progress on splicing, which is getting into the final stage of cross-compatibility tests.
This cannot be used yet with other implementations, but will likely be available in the next release.

This release also contains various performance improvements, more configuration options and bug fixes.
One notable performance improvement is a change in one of our database indexes (see #2946), which may take a few seconds to complete when restarting your node.

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 28.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

This version of Bitcoin Core lets us benefit from opportunistic package relay.
We now more aggressively use the *remote* commitment when using anchor outputs, which ensures that channel funds can be reallocated without waiting for a long delay.

### Offers

You can now create an offer with

```sh
./eclair-cli createoffer --description=coffee --amountMsat=20000 --expireInSeconds=3600 --issuer=me@example.com --blindedPathsFirstNodeId=03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f
```

All parameters are optional and omitting all of them will create a minimal offer with your public node id.
You can also disable offers and list offers with

```sh
./eclair-cli disableoffer --offer=lnoxxx
./eclair-cli listoffers
```

If you specify `--blindedPathsFirstNodeId`, your public node id will not be in the offer, you will instead be hidden behind a blinded path starting at the node that you have chosen.
You can configure the number and length of blinded paths used in `eclair.conf`:

```conf
offers {
  // Minimum length of an offer blinded path
  message-path-min-length = 2

  // Number of payment paths to put in Bolt12 invoices
  payment-path-count = 2
  // Length of payment paths to put in Bolt12 invoices
  payment-path-length = 4
  // Expiry delta of payment paths to put in Bolt12 invoices
  payment-path-expiry-delta = 500
}
```

### Simplified mutual close

This release includes support for the latest [mutual close protocol](https://github.com/lightning/bolts/pull/1205).
This protocol allows both channel participants to decide exactly how much fees they're willing to pay to close the channel.
Each participant obtains a channel closing transaction where they are paying the fees.

Once closing transactions are broadcast, they can be RBF-ed by calling the `close` RPC again with a higher feerate:

```sh
./eclair-cli close --channelId=<channel_id> --preferredFeerateSatByte=<rbf_feerate>
```

### Peer storage

With this release, eclair supports the `option_provide_storage` feature introduced in <https://github.com/lightning/bolts/pull/1110>.
When `option_provide_storage` is enabled, eclair will store a small encrypted backup for peers that request it.
This backup is limited to 65kB and node operators should customize the `eclair.peer-storage` configuration section to match their desired SLAs.
This is mostly intended for LSPs that serve mobile wallets to allow users to restore their channels when they switch phones.

### Eclair requires a  Java 21 runtime

Eclair now targets Java 21 and requires a compatible Java Runtime Environment. It will no longer work on JRE 11 or JRE 17.
There are many organisations that package Java runtimes and development kits, for example [OpenJDK 21](https://adoptium.net/temurin/releases/?package=jdk&version=21).

### API changes

- `createoffer` allows creating a Bolt12 offer managed by eclair (#2976).
- `disableoffer` allows disabling a Bolt12 offer (#2976).
- `listoffers` lists offers managed by eclair (#2976).

### Miscellaneous improvements and bug fixes

#### Gossip sync limits

On reconnection, eclair now only synchronizes its routing table with a small number of top peers instead of synchronizing with every peer.
If you already use `sync-whitelist`, the default behavior has been modified and you must set `router.sync.peer-limit = 0` to keep preventing any synchronization with other nodes.
You must also use `router.sync.whitelist` instead of `sync-whitelist`.

## Verifying signatures

You will need `gpg` and our release signing key 7A73FE77DE2C4027. Note that you can get it:

- from our website: https://acinq.co/pgp/drouinf.asc
- from github user @sstone, a committer on eclair: https://api.github.com/users/sstone/gpg_keys

To import our signing key:

```sh
$ gpg --import drouinf.asc
```

To verify the release file checksums and signatures:

```sh
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
$ sha256sum -c SHA256SUMS.stripped
```

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 24.04.1
- Adoptium OpenJDK 21.0.4

Use the following command to generate the eclair-node package:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

- [4d930c7](https://github.com/ACINQ/eclair/commit/4d930c776bc2e08425c82d31c20a27468d659ea9) Back to dev (#2957)
- [2ad2260](https://github.com/ACINQ/eclair/commit/2ad22602fd332a32c46c9efff1e90cc962a684a5) Refactor Sphinx failures (#2955)
- [feef44b](https://github.com/ACINQ/eclair/commit/feef44b9803a2cc3144289a328a4d0091b0fcacb) Properly type Sphinx shared secrets (#2959)
- [8381fc4](https://github.com/ACINQ/eclair/commit/8381fc4d2bbe81ed35a300bd1b00f188e2e709b2) Decrypt on-the-fly funding trampoline failures (#2960)
- [189e282](https://github.com/ACINQ/eclair/commit/189e28299373b2aada6f51efe3e0b901bfb3cea3) Remove obsolete `WatchFundingConfirmed` when using RBF (#2961)
- [e28f23f](https://github.com/ACINQ/eclair/commit/e28f23fbc886a98e732c0016094d15a35697c373) Peer storage (#2888)
- [61af10a](https://github.com/ACINQ/eclair/commit/61af10ac71ec68cc65d215815f209575794a06ae) Add more splice RBF reconnection tests (#2964)
- [c390560](https://github.com/ACINQ/eclair/commit/c390560aa1f670f6ad95a60fcb4f610ef12e4b0c) Delay considering a channel closed when seeing an on-chain spend (#2936)
- [27ba60f](https://github.com/ACINQ/eclair/commit/27ba60f2416c47c6315139f0cb21894534cad890) `OutgoingNodeId` in a blinded path may not be a wallet (#2970)
- [a35a972](https://github.com/ACINQ/eclair/commit/a35a972081628de4647e8989bef91f4eb6380081) Build against Java 21 (#2929)
- [e99fa2e](https://github.com/ACINQ/eclair/commit/e99fa2e0a4d3900cc72cfa62d1ecb64b46ee52e3) Refactor route finding (#2974)
- [db93cbe](https://github.com/ACINQ/eclair/commit/db93cbeda6eb43f7cd7614d86a0f41b07d99d253) Add support for taproot outputs to our "input info" class (#2895)
- [ef1a029](https://github.com/ACINQ/eclair/commit/ef1a029dff5025f29e2b2e26c477638fd0d851eb) Reestablish partially signed splice with current `remote_commitment_number` (#2965)
- [96183a9](https://github.com/ACINQ/eclair/commit/96183a93aa6090c18384db00ea7aea90658494d0) Increase `min-depth` for funding transactions (#2973)
- [8827a04](https://github.com/ACINQ/eclair/commit/8827a043491014d160aae6b5e86cf5c1578090a7) Get ready for storing partial commit signatures (#2896)
- [1c38591](https://github.com/ACINQ/eclair/commit/1c38591d0bb3bc8564a293a78538a06e2595b7bd) Get rid of various unnecessary warnings in logs (#2981)
- [29ac25f](https://github.com/ACINQ/eclair/commit/29ac25f4c06024a686f157e8a45a3b0f9bfa69e5) Move `recommended_feerates` message to `CONNECTED` state (#2984)
- [3249f2b](https://github.com/ACINQ/eclair/commit/3249f2b6077564030411c27d20ac08b30e0e2545) Validate offers format (#2985)
- [12df4ce](https://github.com/ACINQ/eclair/commit/12df4cecfb356a8e5a99ff48f844d03dfe268d04) Add liquidity griefing protection for liquidity ads (#2982)
- [05f7dc3](https://github.com/ACINQ/eclair/commit/05f7dc3d75624d18cee2b0e668cf14dfb6c73c28) Verify maven dependency checksums (#2986)
- [5a1811b](https://github.com/ACINQ/eclair/commit/5a1811b344dd3b0b36ca694ec22b447b61edbe9a) Rework channel announcement signatures handling (#2969)
- [73ea751](https://github.com/ACINQ/eclair/commit/73ea75105a32cdc90b776e4d0a3e353447de16e8) Do not estimate balance for local channels (#2988)
- [06eb44a](https://github.com/ACINQ/eclair/commit/06eb44af7de28ab7c49b6f9874e393947620ade5) Send `channel_announcement` for splice transactions on public channels (#2968)
- [00fe7a3](https://github.com/ACINQ/eclair/commit/00fe7a32b362a097ec5c5aff9126652d6fbdd6b4) Only sync with top peers (#2983)
- [03ba2f8](https://github.com/ACINQ/eclair/commit/03ba2f861791a64c63713b4d6f307a4f27daf6f2) Fix flaky `ZmqWatcher` test (#2992)
- [b6aa4cc](https://github.com/ACINQ/eclair/commit/b6aa4cc8eccf465daf743cd9cc606dc3c760827f) Log balance estimate updates (#2994)
- [5abf99e](https://github.com/ACINQ/eclair/commit/5abf99efed318df9f8d09fd3bb615131e75aa6fd) Add router support for batched splices (#2989)
- [10edb42](https://github.com/ACINQ/eclair/commit/10edb42b20391d80aa7fbb3e240c78e4b7c77264) Use SHA256 checksum to verify bitcoind download (#2996)
- [8e46889](https://github.com/ACINQ/eclair/commit/8e46889b87d49ba76f7e2feb348075d813b9622e) Use sha256 checksums to verify maven dependencies (#2998)
- [ee70529](https://github.com/ACINQ/eclair/commit/ee7052922c1d791ebc98e97210159c8d59ba9a12) Update logback to 1.5.16 (#2995)
- [7ea73a7](https://github.com/ACINQ/eclair/commit/7ea73a7037365a847e01d4774a9dd3a186cb0354) (Minor) Add a feerate method for funding/closing (#3001)
- [bd08bcd](https://github.com/ACINQ/eclair/commit/bd08bcddd29099fb9278beea45253d51965d58cb) fixup! Update logback to 1.5.16 (#2995) (#3004)
- [35876c4](https://github.com/ACINQ/eclair/commit/35876c4181416a756bc1bca1a439177f63ee0b7f) Compute detailed diff of balance (#3000)
- [a19ca67](https://github.com/ACINQ/eclair/commit/a19ca67cb4a3cb33fbbe83daf863785487df35b1) Fix flaky channel open integration test (#3003)
- [a8787ab](https://github.com/ACINQ/eclair/commit/a8787ab1d57bcb230fd6490cb3183eea11d4378f) Use remote funding when setting `max_htlc_value_in_flight` (#2980)
- [23c139c](https://github.com/ACINQ/eclair/commit/23c139ce91f3d911ca4512df342d56f4d40f80b0) Split composite DB index on `htlc_infos` table (#2946)
- [bc44808](https://github.com/ACINQ/eclair/commit/bc448081645d222e3b7a694be5e9775009798d25) Secondary mechanism to trigger watches for transactions from past blocks (#3002)
- [9b91c16](https://github.com/ACINQ/eclair/commit/9b91c1678e065713b5981f90a978614a17ca6f8f) Fix eclair-cli "compact" mode (#2990)
- [3e5929b](https://github.com/ACINQ/eclair/commit/3e5929b2217c4e349d2d09ccc76ee4f39ae5d736) (Minor) Remove `ChannelOrigin` (#3006)
- [fb58d8c](https://github.com/ACINQ/eclair/commit/fb58d8c7c2c3a63ca9de241f5487dc61048e0e37) Store remote features in `PeersDb` (#2978)
- [372222d](https://github.com/ACINQ/eclair/commit/372222d9f86e6279c93a54213d73405b91697e43) Check peer features before attempting wake-up (#2979)
- [3aac8da](https://github.com/ACINQ/eclair/commit/3aac8da14655886a016aa92055bd8289bf4e3e0d) Implement `option_simple_close` (#2967)
- [194f673](https://github.com/ACINQ/eclair/commit/194f67365fd257169e241586a29e7d785ae85113) Update Bitcoin Core to v28.x (#2962)
- [67e896b](https://github.com/ACINQ/eclair/commit/67e896b37d14b419dd41784bb6c75d695560b721) Use default confirmations for single-funded channel (#3013)
- [00de49f](https://github.com/ACINQ/eclair/commit/00de49f750e3de7ac30b1734186178295b4d02ca) Better handling of invalid public keys in codecs (#3012)
- [22bc4a7](https://github.com/ACINQ/eclair/commit/22bc4a7d16682ace4121570dbea609d7bad6d1b5) (Minor) Fix capturing logs in tests (#3011)
- [8c83a30](https://github.com/ACINQ/eclair/commit/8c83a302f4bc961f2b3241fdc8023e570ff04cd4) Make wallet resolution independent of wake-up config in `NodeRelay` (#3014)
- [4ad2f99](https://github.com/ACINQ/eclair/commit/4ad2f99370ae8f1306f21a1693f90a4a25377dc9) Update bitcoin lib (#3015)
- [9456236](https://github.com/ACINQ/eclair/commit/945623643f885b9a22bacfb0bfb977b78f51bcd8) Allow recipient to pay for blinded route fees (#2993)
- [cae22d7](https://github.com/ACINQ/eclair/commit/cae22d71be0481318cfad9d73456ae2f2d5b09f2) Add scripts for taproot channels (#3016)
- [f6b051c](https://github.com/ACINQ/eclair/commit/f6b051cf7344ede29d0197d9967004f07391b766) Prioritize remote commitment instead of local one (#3019)
- [21917f5](https://github.com/ACINQ/eclair/commit/21917f55dd157dad5fc62c2013f9b5141fc39db1) Add support for your_last_funding_locked and my_current_funding_locked tlvs in channel_reestablish (#3007)
- [37a3f9d](https://github.com/ACINQ/eclair/commit/37a3f9d56c9415d0a869327250985eddd91e6b1a) Allow override peer storage write delay (#3022)
- [c7a288b](https://github.com/ACINQ/eclair/commit/c7a288b91fc19e89683c531cb3e9f61e59deace9) Sort amounts in the balance check (#3023)
- [4729876](https://github.com/ACINQ/eclair/commit/4729876cac776d7817c0aa45640d8009e3afb81a) Add advanced api control methods (#3024)
- [939e25d](https://github.com/ACINQ/eclair/commit/939e25da66ccc205948d642fbd0e4f23c385a985) Add path finding for blinded routes (#3027)
- [95bbf06](https://github.com/ACINQ/eclair/commit/95bbf063c9283b525c2bf9f37184cfe12c860df1) Use channel_reestablish tlv when sending channel_ready (#3025)
- [7ef55a6](https://github.com/ACINQ/eclair/commit/7ef55a6abbdd53bb2bbd1e9703a4a110bb265579) Use confirmed inputs for anchor transactions (#3020)
- [e3b3261](https://github.com/ACINQ/eclair/commit/e3b3261c2cc19e21f70ec4be56f5a851e98625bb) Remove ln explorer links from README.md (#3029)
- [722c9ff](https://github.com/ACINQ/eclair/commit/722c9ffac45dda98c0ae6f6d078674e65e9900be) Manage Bolt 12 offers without extra plugin (#2976)
- [26f06c9](https://github.com/ACINQ/eclair/commit/26f06c955ba00a8346f26cdb6adfe4feeff60106) Gate new splice tlvs on remote support for splicing (#3031)
- [6a8df49](https://github.com/ACINQ/eclair/commit/6a8df49a9bf006a0826b828020f551ecb6c7a33e) Remove spurious interactive-tx `commit_sig` retransmission (#2966)

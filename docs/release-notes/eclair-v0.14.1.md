# Eclair v0.14.1

This is a patch release that contains several bug fixes and performance improvements.
It also updates the minimal version of Bitcoin Core and our bitcoin library.

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 31.x.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

### Disable blinded path fee discount for Bolt12

We've disabled blinded path fee discount introduced in #2993 for Bolt12 payments.
It doesn't work well with MPP and need to be re-designed.
If you're using a custom offer-handler plugin, make sure you don't set `feeOverride_opt`
in the `InvoiceRequestActor.Route` you create, otherwise your node will be at risk.

See #3332 for more details.

## Verifying signatures

You will need `gpg` and our release signing key E04E48E72C205463. Note that you can get it:

- from our website: https://acinq.co/pgp/drouinf2.asc
- from github user @sstone, a committer on eclair: https://api.github.com/users/sstone/gpg_keys

To import our signing key:

```sh
$ gpg --import drouinf2.asc
```

To verify the release file checksums and signatures:

```sh
$ gpg -d SHA256SUMS.asc > SHA256SUMS.stripped
$ sha256sum -c SHA256SUMS.stripped
```

## Building

Eclair builds are deterministic. To reproduce our builds, please use the following environment (*):

- Ubuntu 24.04.1
- Adoptium OpenJDK 21.0.6

Then use the following command to generate the eclair-node packages:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 21, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

- [2dda794](https://github.com/ACINQ/eclair/commit/2dda79468a8b69a2acf7962cdac63245f7cc3ee8) Back to dev (#3313)
- [7fb62fc](https://github.com/ACINQ/eclair/commit/7fb62fc58799857bceb3997b4f461ec7c3911762) Send `splice_locked` if necessary while reconnecting (#3318)
- [cfe47a8](https://github.com/ACINQ/eclair/commit/cfe47a8c3c48cb43a45903b6f6709747fffb48e0) Remove deprecated `bip125 replaceable` field in mempool transaction class (#3319)
- [4b9ba01](https://github.com/ACINQ/eclair/commit/4b9ba01058b677428cb67ebb6c4894a03550d53d) Refactor attribution data (#3320)
- [743dcdd](https://github.com/ACINQ/eclair/commit/743dcdd1a8fbbe3d11fa80946b3cd731920f6988) Use bitcoin-lib 0.48 (#3316)
- [7fb9460](https://github.com/ACINQ/eclair/commit/7fb9460183490260537c2e80c0ce4f1af144ea90) Reject incoming HTLCs with a high `cltv_expiry` (#3323)
- [9b0bcec](https://github.com/ACINQ/eclair/commit/9b0bcec4b1d946b6b1b8c8ba2ae8cf24803bc40e) Reject `temporary_channel_id` duplicates early (#3324)
- [cbafa93](https://github.com/ACINQ/eclair/commit/cbafa93a38ef2e729d9f86fad2a1d6e79ae6a430) Update Bitcoin Core to v31.1 (#3327)
- [823341e](https://github.com/ACINQ/eclair/commit/823341e6e3d603be90a57343f79a726f65ab342f) Ignore repeated invalid `tx_signatures` (#3328)
- [7951924](https://github.com/ACINQ/eclair/commit/79519244952d321a0579768baf5d15297d29a400) Accept Bolt12 invoices with reply path (#3325)
- [3eebbe9](https://github.com/ACINQ/eclair/commit/3eebbe96188f93170b6015fd3abaa9c8c4bf5d42) Don't store duplicate settlement messages (#3336)
- [e4e1a19](https://github.com/ACINQ/eclair/commit/e4e1a19d913279b3cdea1feefee1ec8372735172) Reject `start_batch` with size <= 1 (#3333)
- [687485f](https://github.com/ACINQ/eclair/commit/687485fc3ef8d98c2c08b67190fb1f17c95bb2cf) Apply RBF limits to remote closing transactions (#3331)
- [45ea9fb](https://github.com/ACINQ/eclair/commit/45ea9fbfb707d11c69508ab4cb072c1cbe5502cc) Prevent `channel_id` collisions (#3337)
- [3397b1a](https://github.com/ACINQ/eclair/commit/3397b1a642690f52f5ab6591ca9d6e19783b4c04) Disable Bolt12 recipient path fee discount (#3332)

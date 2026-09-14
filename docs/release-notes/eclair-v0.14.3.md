# Eclair v0.14.3

This is a patch release that contains security hardening fixes, most originating from project Loupe.
We highly recommend upgrading, as some of these issues can be exploited by malicious nodes.

### Configuration changes

#### Add `max-funding-feerate` configuration parameter

The feerate used for funding and splice transactions comes from our fee estimator. We added a new configuration value
to `eclair.conf` to cap that feerate, so that inaccurate fee estimates cannot make you pay arbitrarily high mining fees
on every channel open or splice, similarly to what `max-closing-feerate` does for closing transactions:

```conf
// Maximum feerate that will be used for funding and splice transactions, in satoshis per byte.
eclair.on-chain-fees.max-funding-feerate = 50
```

If your channel opens and splices don't confirm because the mempool is more congested than that, you can RBF them
with the `rbfopen` and `rbfsplice` API commands, or increase this value and restart your node.

### Miscellaneous improvements and bug fixes

- Tor: allow password authentication on private networks
- PeerScorer: improve logging

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

- [39c5cb2](https://github.com/ACINQ/eclair/commit/39c5cb27618eb4eb0cef16d6ff9b2c8f9b1475c8) Back to dev (#3371)
- [232f721](https://github.com/ACINQ/eclair/commit/232f721b39398b4a20ca896b6e24b64c53df069e) Improvements in `PeerScorer` (#3365)
- [dd6587d](https://github.com/ACINQ/eclair/commit/dd6587dc85703e7f0b3023d18f0aa5b4c85e1087) Optional setting to dip into trampoline relay fees (#3372)
- [8b58405](https://github.com/ACINQ/eclair/commit/8b58405db12a92286d4943cc2e2c8b6b1fa2f310) Tor: allow password auth on private networks (#3375)
- [4111ad8](https://github.com/ACINQ/eclair/commit/4111ad86f960c017537326ebf3087f1b4b6104bd) More AI fixes and defense-in-depth (#3376)
- [eb7cb00](https://github.com/ACINQ/eclair/commit/eb7cb009ab00170c393246563a5325a937375821) Improve the deterministic build (#3378)



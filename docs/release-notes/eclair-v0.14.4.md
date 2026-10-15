# Eclair vnext

This is a patch release that contains security hardening fixes, most originating from project Loupe.
We highly recommend upgrading, as some of these issues can be exploited by malicious nodes.

### API changes

The API now rejects requests that carry an `Origin` header, which means it cannot be used from a web browser anymore.

Our API relies on HTTP basic authentication, which web browsers attach to cross-site requests once it has been cached:
any web page the node operator visits could then forge authenticated API calls, and since our endpoints accept
form-encoded parameters, a plain HTML form is enough (the attacker cannot read the response, but the API call has
already been made). Browsers set the `Origin` header on those requests, while `curl` and `eclair-cli` never do.

Command-line usage is unaffected. If you were serving a web front-end for the API, you now need to put a back-end of
your own in front of it.

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

- [bafcf3b](https://github.com/ACINQ/eclair/commit/bafcf3b6fc5299a4aa861f6ae84303a26579af5b) Back to dev (#3379)
- [27438b7](https://github.com/ACINQ/eclair/commit/27438b75ba8117fe5af31d588030bd874a433138) Reject API access from browsers (#3380)
- [c3e9f5d](https://github.com/ACINQ/eclair/commit/c3e9f5d633050249a745ce98b63991ed7c266340) Update build to improve release process (#3381)
- [6162e2a](https://github.com/ACINQ/eclair/commit/6162e2aa8f0c696e5e8c7cd5168f0a08ade4c6d0) Use bitcoin-kmp 0.49 (depends on bitcoin-kmp 0.32.0 and secp256k1-kmp 0.24.0) (#3362)
- [259ea64](https://github.com/ACINQ/eclair/commit/259ea647580b5bb74d6d63e3a1d5ea81bdaad030) Fix flaky tests caused by port allocation race (#3384)
- [972dfe9](https://github.com/ACINQ/eclair/commit/972dfe995de0a24e8b496c6cf7144e7db377d51d) Upgrade postgresql client from 42.7.11 to 42.7.12 (#3330)
- [746fcb1](https://github.com/ACINQ/eclair/commit/746fcb1162018c7248b1d4538b555174da77a431) Bound feerate of remote rbf when we contribute (#3388)
- [a07fff5](https://github.com/ACINQ/eclair/commit/a07fff56d31291a7bc36cf1bb0faa0f1252c9774) Reject seed files with an invalid length (#3389)
- [e43fbf6](https://github.com/ACINQ/eclair/commit/e43fbf69255ecf40ea06cc5efd57a51cccdc26f9) Harden interactive-tx funding against a malicious bitcoind (#3390)
- [380feec](https://github.com/ACINQ/eclair/commit/380feec76b0460c06174e3bdaff5a3a28f25618f) Validate remote closing script updates (#3387)
- [c89af11](https://github.com/ACINQ/eclair/commit/c89af1154673e7fd9eed3142217377ca1d25d8fb) Remove `spendfromchanneladdress` nonce after one sig (#3392)
- [248893a](https://github.com/ACINQ/eclair/commit/248893a3a59a78b6114152477d1b6e102f2c6a3d) Use published commitment in PostRestartHtlcCleaner (#3393)
- [6b61581](https://github.com/ACINQ/eclair/commit/6b61581fc35cb48cdd6fd8d4502053323369e5fb) Only relay on-the-fly payment on funded channel (#3394)
- [d3a146a](https://github.com/ACINQ/eclair/commit/d3a146a085953389e244623e53f940b5ab897692) Reject onion per-hop lengths exceeding packet (#3395)
- [af99418](https://github.com/ACINQ/eclair/commit/af994180487cf173a1bb20b0835bda1d6b52b4d5) Reject unpublishable splices (#3396)
- [c96478a](https://github.com/ACINQ/eclair/commit/c96478a8259648ac1744b47770a719a190a8eb09) Settle relayed on-the-fly upstreams on hash reuse (#3399)
- [9a1ecc9](https://github.com/ACINQ/eclair/commit/9a1ecc99816f2b11dee25824dd03a50072f30fe1) Force-close again when a splice RBF sibling confirms (#3400)
- [7686dfa](https://github.com/ACINQ/eclair/commit/7686dfadff7c6868cb3ce441550a22f1152b7dff) Keep unsigned settlements when rejecting commands (#3401)
- [21f1bc8](https://github.com/ACINQ/eclair/commit/21f1bc8db415a46a8cee690e2d067e746764e828) Retry publishing txs evicted by unconfirmed conflicts (#3397)

# Eclair vnext

<insert here a high-level description of the release>

## Major changes

### Update minimal version of Bitcoin Core

With this release, eclair requires using Bitcoin Core 27.1.
Newer versions of Bitcoin Core may be used, but have not been extensively tested.

This version introduces a new coin selection algorithm called  [CoinGrinder](https://github.com/bitcoin/bitcoin/blob/master/doc/release-notes/release-notes-27.0.md#wallet) that will reduce on-chain transaction costs when feerates are high.

To enable CoinGrinder at all fee rates and prevent the automatic consolidation of UTXOs, add the following line to your `bitcoin.conf` file:

```conf
consolidatefeerate=0
```

### Incoming obsolete channels will be rejected

Eclair will not allow remote peers to open new `static_remote_key` channels. These channels are obsolete, node operators should use `option_anchors` channels now.
Existing `static_remote_key` channels will continue to work. You can override this behaviour by setting `eclair.channel.accept-incoming-static-remote-key-channels` to true.

Eclair will not allow remote peers to open new obsolete channels that do not support `option_static_remotekey`.

### Local reputation and HTLC endorsement

To protect against jamming attacks, eclair gives a reputation to its neighbors and uses to decide if a HTLC should be relayed given how congested is the outgoing channel.
The reputation is basically how much this node paid us in fees divided by how much they should have paid us for the liquidity and slots that they blocked. 
The reputation is per incoming node and endorsement level.
The confidence that the HTLC will be fulfilled is transmitted to the next node using the endorsement TLV of the `update_add_htlc` message.

To configure, edit `eclair.conf`:
```eclair.conf
eclair.local-reputation {
    # Reputation decays with the following half life to emphasize recent behavior.
    half-life = 7 days
    # HTLCs that stay pending for longer than this get penalized
    good-htlc-duration = 12 seconds
    # How much to penalize pending HLTCs. A pending HTLC is considered equivalent to this many fast-failing HTLCs.
    pending-multiplier = 1000
}
```

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

<insert changes>

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

- Ubuntu 22.04
- AdoptOpenJDK 11.0.22
- Maven 3.9.2

Use the following command to generate the eclair-node package:

```sh
mvn clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.10.0... --format=oneline --reverse`>

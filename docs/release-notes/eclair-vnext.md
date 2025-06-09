# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### bLIP-18 Inbound Routing Fees

Eclair now supports [bLIP-18 inbound routing fees](https://github.com/lightning/blips/pull/18) which proposes an optional 
TLV for channel updates that allows node operators to set (and optionally advertise) inbound routing fee discounts, enabling 
more flexible fee policies and incentivizing desired incoming traffic.

#### Configuration

| Configuration Parameter                                                          | Default Value | Description                                                                                                                                              |
|----------------------------------------------------------------------------------|---------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `eclair.router.path-finding.default.blip18-inbound-fees`                         | `false`       | enables support for bLIP-18 inbound routing fees                                                                                                         | 
| `eclair.router.path-finding.default.exclude-channels-with-positive-inbound-fees` | `false`       | enables exclusion of channels with positive inbound fees from path finding, helping to prevent `FeeInsufficient` errors and ensure more reliable routing |

The routing logic considers inbound fees during route selection if enabled. New logic is added to exclude channels with 
positive inbound fees from route finding when configured. The relay and route calculation logic now computes total fees 
as the sum of the regular (outbound) and inbound fees when applicable.

The wire protocol is updated to include the new TLV (0x55555) type for bLIP-18 inbound fees in ChannelUpdate messages.
Code that (de)serializes channel updates now handles these new fields.

New database tables and migration updates for storing inbound fee information per peer.

### API changes

<insert changes>

- `updaterelayfee` now accepts optional `--inboundFeeBaseMsat` and `--inboundFeeProportionalMillionths` parameters. If omitted, existing inbound fees will be preserved.

### Miscellaneous improvements and bug fixes

<insert changes>

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

Use the following command to generate the eclair-node package:

```sh
./mvnw clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 21, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.12.0... --format=oneline --reverse`>

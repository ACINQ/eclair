# Eclair vnext

<insert here a high-level description of the release>

## Major changes

<insert changes>

### API changes

<insert changes>

### Miscellaneous improvements and bug fixes

#### Delay enforcement of new channel fees

When updating the relay fees for a channel, eclair can now continue accepting to relay payments using the old fee even if they would be rejected with the new fee.
By default, eclair will still accept the old fee for 10 minutes, you can change it by setting `eclair.relay.fees.enforcement-delay` to a different value.

If you want a specific fee update to ignore this delay, you can update the fee twice to make eclair forget about the previous fee.

#### New minimum funding setting for private channels

New settings have been added to independently control the minimum funding required to open public and private channels to your node.

The `eclair.channel.min-funding-satoshis` setting has been deprecated and replaced with the following two new settings and defaults:

* `eclair.channel.min-public-funding-satoshis = 100000`
* `eclair.channel.min-private-funding-satoshis = 100000`

If your configuration file changes `eclair.channel.min-funding-satoshis` then you should replace it with both of these new settings.

#### Expired incoming invoices now purged if unpaid

Expired incoming invoices that are unpaid will be searched for and purged from the database when Eclair starts up. Thereafter searches for expired unpaid invoices to purge will run once every 24 hours. You can disable this feature, or change the search interval with two new settings:

* `eclair.purge-expired-invoices.enabled = true
* `eclair.purge-expired-invoices.interval = 24 hours`

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

- Ubuntu 20.04
- AdoptOpenJDK 11.0.6
- Maven 3.8.1

Use the following command to generate the eclair-node package:

```sh
mvn clean install -DskipTests
```

That should generate `eclair-node/target/eclair-node-<version>-XXXXXXX-bin.zip` with sha256 checksums that match the one we provide and sign in `SHA256SUMS.asc`

(*) You may be able to build the exact same artefacts with other operating systems or versions of JDK 11, we have not tried everything.

## Upgrading

This release is fully compatible with previous eclair versions. You don't need to close your channels, just stop eclair, upgrade and restart.

## Changelog

<fill this section when publishing the release with `git log v0.7.0... --format=oneline --reverse`>

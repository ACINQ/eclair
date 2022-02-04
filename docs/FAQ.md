# FAQ

## What does it mean for a channel to be "enabled" or "disabled" ?

A channel is disabled if a `channel_update` message has been broadcast for that channel with the `disable` bit set (see [BOLT 7](https://github.com/lightning/bolts/blob/master/07-routing-gossip.md#the-channel_update-message)). It means that the channel still exists but cannot be used to route payments, until it has been re-enabled.

Suppose you're A, with the following setup:
```
A ---ab--> B --bc--> C
```
And node C goes down. B will publish a channel update for channel `bc` with the `disable` bit set.
There are other cases when a channel becomes disabled, for example when its balance goes below reserve...

Note that you can have multiple channels between the same nodes, and that some of them can be enabled while others are disabled (i.e. enable/disable is channel-specific, not node-specific).

## How should you stop an Eclair node ?

To stop your node you just need to kill its process, there is no API command to do this. The JVM handles the quit signal and notifies the node to perform clean-up. For example, there is a hook to cleanly free DB locks when using Postgres.

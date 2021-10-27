# Advanced usage

## Avoid mass force-close of channels

In order to minimize force-closes of channels (especially for larger nodes), it is possible to customize the way eclair handles certain situations, like outdated commitment and internal errors.

:warning: There is no magic: non-default strategies are a trade-off where it is assumed that the node is closely monitored. Instead of automatically reacting to some events, eclair will stop and await manual intervention. It is therefore reserved for advanced or professional node operators. Default strategies are best suited for smaller loosely administered nodes.

### Outdated commitments

The default behavior, when our peer tells us (or proves to us) that our channel commitment is outdated, is to request a remote force-close of the channel (a.k.a. recovery).

It may happen that due to a misconfiguration, the node was accidentally restarted using e.g. an old backup, and the data wasn't really lost. In that case, simply fixing the configuration and restarting eclair would prevent a mass force-close of channels.

This is why an alternative behavior is to simply log an error and stop the node. However, because our peer may be lying when it tells us that our channel commitment data is outdated, there is a 10 min window after restart when this strategy applies. After that, the node reverts to the default strategy.

During the 10 min window, the operator should closely monitor the node and assess, if the peer stops, whether this is really a case of using outdated data, or a peer is just lying. If it turns out that the data is really outdated due to a misconfiguration, the operator has an opportunity to fix it and restart the node. If the data is really outdated because it was simply lost, then the operator should change the strategy to the default and restart the node: this will cause the force close of outdated channels, but there is no way to avoid that.

Here is a decision tree:
```
if (node stops after restart)
  if (false positive)
    configure eclair to use default strategy and restart node (will force close channels to malicious peers)
  else
    if (more up-to-date data available)
      configure eclair to point to proper database and restart node
    else
      configure eclair to use default strategy and restart node (will force close all outdated channels)
```

The alternate strategy can be configured by setting `eclair.outdated-commitment-strategy=stop`  (see [`reference.conf`](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf)).

### Unhandled exceptions

The default behavior, when we encounter an unhandled exception or internal error, is to locally force-close the channel.

Not only is there a delay before the channel balance gets refunded, but if the exception was due to some misconfiguration or bug in eclair that affects all channels, we risk force-closing all channels.

This is why an alternative behavior is to simply log an error and stop the node. Note that if you don't closely monitor your node, there is a risk that your peers take advantage of the downtime to try and cheat by publishing a revoked commitment. Additionally, while there is no known way of triggering an internal error in eclair from the outside, there may very well be a bug that allows just that, which could be used as a way to remotely stop the node (with the default behavior, it would "only" cause a local force-close of the channel).

The alternate strategy can be configured by setting `eclair.unhandled-exception-strategy=stop`  (see [`reference.conf`](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf)).
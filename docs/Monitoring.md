# Monitoring Eclair

Eclair uses [Kamon](https://kamon.io/) to generate metrics and spans. Kamon automatically collects
many akka-related metrics and performance counters from the underlying system.

Kamon uses an agent ([Kanela](https://github.com/kamon-io/kanela)) that runs alongside eclair and
periodically sends metrics and spans to a monitoring backend.

## Enabling monitoring with Kamon APM

Monitoring is disabled by default. To enable monitoring with Kamon's hosted platform, create an
account on their website and add the following to your `eclair.conf`:

```config
eclair.enable-kamon=true

kamon {

  apm {
    # Put the API key obtained from your Kamon account.
    api-key=XXXXXXX
  }

  trace {
    # Configures a sampler that decides which traces should be reported to the trace backends. The possible values are:
    #   - always: report all traces (will impact application performance).
    #   - never:  don't report any trace.
    #   - random: randomly decide using the probability defined in the random-sampler.probability setting.
    #   - adaptive: keeps dynamic samplers for each operation while trying to achieve a set throughput goal.
    sampler = "random"
  }

}
```

When starting eclair, you should enable the Kanela agent:

```sh
eclair.sh -with-kanela
```

Your eclair node will start exporting monitoring data to Kamon.
You can then start creating dashboards, graphs and alerts directly on Kamon's website.

## Enabling monitoring with a different backend

Kamon supports many other monitoring [backends](https://kamon.io/docs/latest/reporters/).
This can be useful for nodes that don't want to export any data to third-party servers.

No specific work has been done yet in eclair to support these backends. If you'd like to use them,
don't hesitate to ask around or send a PR.

## Example metrics

Apart from akka and system metrics, eclair generates a lot of lightning metrics. The following
metrics are just a small sample of all the metrics we provide:

* Number of local channels, grouped by their current state (offline, normal, closing, etc)
* Relayed payments and fees collected
* Number of connected peers
* Bitcoin wallet balance
* Various metrics about the public graph (nodes, channels, updates, etc)

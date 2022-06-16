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

## Enabling monitoring with Prometheus

Kamon supports many other monitoring [backends](https://kamon.io/docs/latest/reporters/).
This can be useful for nodes that don't want to export any data to third-party servers.

Eclair currently supports exporting metrics to [Prometheus](https://kamon.io/docs/latest/reporters/prometheus/).
To enable monitoring with Prometheus, add the following to your `eclair.conf`:

```config
eclair.enable-kamon=true
kamon {
  prometheus {
    start-embedded-http-server = yes
    embedded-server {
      hostname = 0.0.0.0
      port = <port to expose to prometheus>
    }
  }
}
```
Note: By default kamon apm reporter will initialize when you enable kamon in eclair.conf. To disable kamon apm-reporter, add following to your `eclair.conf`
```disable
eclair.enable-kamon=true
kamon.modules {
  apm-reporter {
    enabled = false
  }
} 
```

You should then configure your Prometheus process to scrape metrics from the exposed http server. 
* Download the Prometheus from [here](https://prometheus.io/download/).
* Configure the `prometheus.yml` file present in prometheus repository and add following in it
```prometheus.yml
global:
  scrape_interval:     15s # By default, scrape targets every 15 seconds.

  # Attach these labels to any time series or alerts when communicating with
  # external systems (federation, remote storage, Alertmanager).
  external_labels:
    monitor: 'codelab-monitor'

# A scrape configuration containing exactly one endpoint to scrape:
# Here it's Prometheus itself.
scrape_configs:
  # The job name is added as a label `job=<job_name>` to any timeseries scraped from this config.
  - job_name: 'eclair'

    # Override the global default and scrape targets from this job every 5 seconds.
    scrape_interval: 5s

    static_configs:
      - targets: ['0.0.0.0:9090']
```
After making changes in the prometheus.yml file, start Prometheus and check the status of Prometheus by `sudo systemctl status prometheus`. If it is actively running, you can visit the Prometheus web interface at a specified target in `prometheus.yml`.
## Example metrics

Apart from akka and system metrics, eclair generates a lot of lightning metrics. The following
metrics are just a small sample of all the metrics we provide:

* Number of local channels, grouped by their current state (offline, normal, closing, etc)
* Relayed payments and fees collected
* Number of connected peers
* Bitcoin wallet balance
* Various metrics about the public graph (nodes, channels, updates, etc)

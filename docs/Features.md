# Customize Features

Eclair ships with a set of features that are activated by default, and some experimental or optional features that can be activated by users.
The list of supported features can be found in the [reference configuration](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf).

To enable a non-default feature, you simply need to add the following to your `eclair.conf`:

```conf
eclair.features {
    official_feature_name = optional|mandatory
}
```

For example, to activate `option_static_remotekey`:

```conf
eclair.features {
    option_static_remotekey = optional
}
```

Note that you can also disable some default features:

```conf
eclair.features {
    initial_routing_sync = disabled
}
```

It's usually risky to activate non-default features or disable default features: make sure you fully understand a feature (and the current implementation status, detailed in the release notes) before doing so.

Eclair supports per-peer features. Suppose you are connected to Alice and Bob, you can use a different set of features with Alice than the one you use with Bob. When experimenting with non-default features, we recommend using this to scope the peers you want to experiment with.

This is done with the `override-features` configuration parameter in your `eclair.conf`:

```conf
eclair.override-features = [
    {
        nodeId = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"
        features {
            initial_routing_sync = disabled
            option_static_remotekey = optional
        }
    },
    {
        nodeId = "<another nodeId>"
        features {
            option_static_remotekey = optional
            option_support_large_channel = optional
        }
    },
]
```
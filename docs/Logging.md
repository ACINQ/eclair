# Logging

### Customize Logging

Eclair uses [logback](https://logback.qos.ch/) for logging.
By default the logging level is set to `INFO` for the whole application.
To use a different configuration, and override the internal `logback.xml`, run:

```shell
eclair-node.sh -Dlogback.configurationFile=/path/to/logback-custom.xml
```

If you want parts of the application to log at the `DEBUG` logging level, you will also need to override `akka.loglevel`:

```shell
eclair-node.sh -Dakka.loglevel=DEBUG -Dlogback.configurationFile=/path/to/logback-custom.xml
```

You can find the default `logback.xml` file [here](https://github.com/ACINQ/eclair/blob/master/eclair-node/src/main/resources/logback.xml). It logs everything more serious than `INFO` to a rolling file.

If you want to debug an issue, you can change the root logger's log level to `DEBUG`:

```xml
<root level="DEBUG">
    <appender-ref ref="ROLLING"/>
</root>
```

This setting will produce a lot of logs. If you are investigating an issue with payments, you can instead turn on `DEBUG` logging only for payments-related components by adding a new `logger` before the `root`:

```xml
<logger name="fr.acinq.eclair.payment" level="DEBUG"/>

<root level="INFO">
    <appender-ref ref="ROLLING"/>
</root>
```

On the contrary, if you want a component to emit less logs, you can set its log level to `WARN` or `ERROR`:

```xml
<logger name="fr.acinq.eclair.router" level="WARN"/>

<root level="INFO">
    <appender-ref ref="ROLLING"/>
</root>
```

To figure out the `name` you should use for the `logger` element, you need to look at the hierarchy of the source code in Eclair's [Github repository](https://github.com/ACINQ/eclair). You can even configure loggers for each specific class you're interested in:

```xml
<logger name="fr.acinq.eclair.router" level="WARN"/>
<logger name="fr.acinq.eclair.crypto.Sphinx" level="DEBUG"/>

<root level="INFO">
    <appender-ref ref="ROLLING"/>
</root>
```

For more advanced use-cases, please see logback's [official documentation](https://logback.qos.ch/documentation.html).
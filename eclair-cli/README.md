# Eclair CLI

This project is a Command Line Interface for an Eclair node. It replaces the previous `eclair-cli` bash file. It interacts with the node using the HTTP API exposed by default on port `8080`. To enable this API, visit [this page](https://github.com/ACINQ/eclair/wiki/API).

## Features

- Describe every available commands ;
- Describe options for each command ;
- Better user feedback and error management ;
- Can output the raw JSON response from the node, or for some calls, format the output for better readability ;
- Store global CLI options in `~/.eclair/eclair-cli.conf`

## Developers

### Picocli

This project uses the [Picocli library](https://github.com/remkop/picocli), with annotations to describe the various commands/options.

Example: 

```java
@CommandLine.Command(name = "channel", description = "Returns detailed information about a local channel.", sortOptions = false)
public class Channel extends BaseSubCommand {

  @CommandLine.Option(names = { "--channelId", "-c" }, required = true, description = "Id of a channel (32 bytes HexString)")
  String channelId;

  @Override
  public Integer call() throws Exception {
    // http call...
    return 0;
  }
}
```

### Native binary 
This project leverages GraalVM to generate a native binary. 

To build this image, you must first:

- install Graal VM: https://www.graalvm.org/docs/getting-started/#install-graalvm
- install native image: https://www.graalvm.org/docs/reference-manual/aot-compilation/

Then:

```shell
# build the (assembly)JAR file
mvn clean package
```

Then:

```shell
# build the native binary
native-image -jar target/eclair-cli-x.y.z.jar
```

## Limitations

Note that GraalVM support for Windows is still limited, and native binaries for Windows are as such not available.

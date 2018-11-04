## How to Use Tor with Eclair

### Installing Tor on your node

For Linux:

```shell
sudo apt install tor
```

For Mac OS X:

```shell
brew install tor
```

Edit Tor configuration file `/etc/tor/torrc` (Linux) or `/usr/local/etc/tor/torrc` (Mac OS X)
eclair requires for safe cooke authentication as well as SOCKS5 and control connections to be enabled.
Change value of ExitPolicy parameter only if you really know what you are doing.


```
SOCKSPort 9050
ControlPort 9051
CookieAuthentication 1
ExitPolicy reject *:*
```

Make sure eclair is allowed to read Tor's cookie file (typically `/var/run/tor/control.authcookie`)

### Start Tor

For Linux:

```shell
sudo systemctl start tor
```

For Mac OS X:

```shell
brew services start tor
```

### Configure eclair to use Tor

To enable Tor support simply set `eclair.tor.enabled` parameter in `eclair.conf` to true.

```
eclair.tor.enabled = true
```

By default all traffic will be forwarded through Tor network. Note that in this case the value of `eclair.server.public-ip`
will be ignored and incoming connections will be disabled. To enable incoming connections you
need to configure Tor hidden service using `eclair.tor.protocol-version` parameter.

```
eclair.tor.protocol-version = "v3"
```

There are three possible values for protocol-version:

value  | description
-------|---------------------------------------------------------
 socks | no incoming connections allowed.
 v2    | eclair sets up a Tor hidden service version 2 end point
 v3    | eclair sets up a Tor hidden service version 3

Note, that bitcoind should be configured to use Tor as well.
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

For Windows:
  
Download the "Expert Bundle" from https://www.torproject.org/download/download.html and extract it to the root of your drive (e.g. `C:\tor-win32-0.3.5.7`).

Edit Tor configuration file:
 - `/etc/tor/torrc` (Linux)
 - `/usr/local/etc/tor/torrc` (Mac OS X)
 - `C:\Windows\ServiceProfiles\LocalService\AppData\Roaming\tor\torrc` (Windows)

```
SOCKSPort 9050
ControlPort 9051
CookieAuthentication 1
ExitPolicy reject *:*
```

Eclair requires safe cookie authentication as well as SOCKS5 and control connections to be enabled.
Change the value of the `ExitPolicy` parameter only if you really know what you are doing.

Make sure eclair is allowed to read Tor's cookie file (typically `/var/run/tor/control.authcookie`).

### Start Tor

For Linux:

```shell
sudo systemctl start tor
```

For Mac OS X:

```shell
brew services start tor
```

For Windows:

Open a CMD with administrator access

```shell
tor --service install
```

### Configure Tor hidden service

To create a Tor hidden service endpoint simply set the `eclair.tor.enabled` parameter in `eclair.conf` to true.
```
eclair.tor.enabled = true
```
Eclair will automatically set up a hidden service endpoint and add its onion address to the `server.public-ips` list.
You can see what onion address is assigned using `eclair-cli`:

```shell
eclair-cli getinfo
```
Eclair saves the Tor endpoint's private key in `~/.eclair/tor_pk`, so that it can recreate the endpoint address after 
restart. If you remove the private key eclair will regenerate the endpoint address.   

There are two possible values for `protocol-version`:

```
eclair.tor.protocol-version = "v3"
```

value   | description
--------|---------------------------------------------------------
 v2     | set up a Tor hidden service version 2 end point
 v3     | set up a Tor hidden service version 3 end point (default)
 
Tor protocol v3 (supported by Tor version 0.3.3.6 and higher) is backwards compatible and supports 
both v2 and v3 addresses. 

To create a new Tor circuit for every connection, use `stream-isolation` parameter:

```
eclair.tor.stream-isolation = true
```

For increased privacy do not advertise your IP address in the `server.public-ips` list, and set your binding IP to `localhost`:
```
eclair.server.binding-ip = "127.0.0.1"
```

### Configure SOCKS5 proxy

By default all incoming connections will be established via Tor network, but all outgoing will be created via the 
clearnet. To route them through Tor you can use Tor's SOCKS5 proxy. Add this line in your `eclair.conf`:
```
eclair.socks5.enabled = true
```
You can use SOCKS5 proxy only for specific types of addresses. Use `eclair.socks5.use-for-ipv4`, `eclair.socks5.use-for-ipv6`
or `eclair.socks5.use-for-tor` for fine tuning.

:warning: Tor hidden service and SOCKS5 are independent options. You can use just one of them, but if you want to get the most privacy 
features from using Tor use both.  

Note, that bitcoind should be configured to use Tor as well (https://en.bitcoin.it/wiki/Setting_up_a_Tor_hidden_service).
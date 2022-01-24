## How to _clusterize_ your Eclair node

Eclair allows you to scale up one _logical_ lightning node across multiple servers.

Front servers take care of routing table related gossip and syncing requests from peers, which is cpu/bandwidth intensive. The backend server can focus on core channel management. So, BOLT 1&7 messages are handled in the frontend, while BOLT 2 messages go through and are processed in the backend.

Front servers are stateless, they can be stopped/killed at will. The node will remain operational and reachable as long as there is at least one `frontend` available.

```
                +---+     +-----------+
                |   |     | +-------+ |
                |   |-----|-|       | |
       P   -----| L |-----|-| FRONT |-|---,
       U        | O |-----|-|       | |    \
       B     ---| A |     | +-------+ |     \
       L        | D |     |           |      \
       I        |   |     | +-------+ |       \ +------+
       C   -----| B |-----|-|       | |        `|      |
                | A |-----|-| FRONT |-|---------| BACK |<-- channels management
       N        | L |-----|-|       | |        ,|      |    relay logic
       E    ----| A |     | +-------+ |       / +------+
       T        | N |     |           |      /
       W        | C |     | +-------+ |     /
       O        | E |-----|-|       | |    /
       R   -----| R |-----|-| FRONT |-|---'
       K        |   |-----|-|       |<------- connection management
                |   |     | +-------+ |       routing table sync
                +---+     +-----------+
```

The goal is to offload your node from connection and routing table management:
- incoming connections
- outgoing connections
- gossip queries + pings
- incoming gossip aggregation
- outgoing gossip dispatch (rebroadcast)

### Prerequisite

You already have a lightning node up and running in a standalone setup (with Bitcoin Core properly configured, etc.).

You know what your `node id` is.

Conventions used in this document:
- what we previously called `eclair-node` will be called `backend`. It is to be launched, configured and backed-up exactly like in a standalone setup.
- `node` refer to *akka cluster nodes*, not to be confused with lightning nodes. Together, all *cluster nodes* form a single logical *lighting node*.

### Minimal/Demo setup

Use this if you want to experiment with the cluster mode on a single local server.

Set the following values in `.eclair/eclair.conf`:
```
akka.actor.provider = cluster
akka.extensions = ["akka.cluster.pubsub.DistributedPubSub"]

// replace this with your node id
// if you don't know what your node id is, you should probably stop right here
eclair.front.pub = 03...............
```

Start the `backend`:
```shell
$ ./eclair-node.sh
```

Then run an instance of `frontend`:
```shell
$ ./eclair-front.sh -Dakka.remote.artery.canonical.port=25521 -Declair.server.port=9736
```

NB: we override the ports, otherwise they would conflict since in this example everything runs on the same server. You can run multiple `frontend`s on the same server, just make sure to change the ports.

### Production setup

In production you should:
- run multiple `frontend` servers
- run one app per server
- enable `tcp-tls` to encrypt communications between members of the cluster with your own generated certificate (see below)
- use a load balancer to hide all your `frontend` servers under the same ip address
- set firewall rules to disable lightning connections (port 9735) on your `backend` server, so all connections go through the `frontend`
- enable [monitoring](Monitoring.md)
- on AWS, use AWS Secrets Manager (see [AWS deployment](#aws-deployment))

#### Enable encrypted communication for the cluster

We use a self-signed certificate, which offers a good compromise. More advanced options are available, see [akka doc](https://doc.akka.io/docs/akka/current/remoting-artery.html#remote-security).
> Have a single set of keys and a single certificate for all nodes and disable hostname checking
> - The single set of keys and the single certificate is distributed to all nodes. The certificate can be self-signed as it is distributed both as a certificate for authentication but also as the trusted certificate.
> - If the keys/certificate are lost, someone else can connect to your cluster.
> - Adding nodes to the cluster is simple as the key material can be deployed / distributed to the new cluster node.

Generate a self-signed certificate (set a strong password):
```shell
$ keytool -genkeypair -v \
          -keystore akka-cluster-tls.jks \
          -dname "O=ACME, C=FR" \
          -keypass <password> \
          -storepass <password> \
          -keyalg RSA \
          -keysize 4096 \
          -validity 9999
```

Copy the resulting certificate to the `.eclair` directory on your backend node and all your frontend nodes:
```shell
$ cp akka-cluster-tls.jks ~/.eclair
```
Add this to `eclair.conf` on all your frontend nodes:
```
akka.remote.artery.transport = "tls-tcp"
```

#### Run cluster nodes on separate servers

Start all your frontend nodes with the following environment variables:
* BACKEND_IP set to the IP address of your backend node
* LOCAL_IP set to the IP address of this frontend node (this is typically a private IP address, reachable from your backend node)
* NODE_PUB_KEY set to your node public key
* AKKA_TLS_PASSWORD set to the password of your Akka certificate

Add this to `eclair.conf` on your backend node:
```
akka.remote.artery.transport = "tls-tcp"
akka.remote.artery.canonical.hostname="ip-of-this-backend-node"
akka.cluster.seed-nodes=["akka://eclair-node@ip-of-this-backend-node:25520"]
```

Start your backend node with the following environment variables:
* AKKA_TLS_PASSWORD set to the password of your Akka certificate

### AWS Deployment

For convenience, we provide a prebuilt AWS Beanstalk bundle for the `frontend` (choose a WebServer environment type, and Java platform).

You can run it as-is for testing.

#### TLS encryption

If you intend to use it in production, you need to enable encryption with your own certificate:
1. Follow the procedure above to generate your `akka-tls.jks`

2. We recommend forking the project and building your own bundle:
```shell
$ git clone git@github.com:ACINQ/eclair.git
$ vi eclair-core/src/main/reference.conf # set akka.remote.artery.transport = "tls-tcp"
$ cp akka-cluster-tls.jks eclair-front/modules/awseb/ # copy the file you generated
$ vi eclair-front/modules/awseb.xml # uncomment the relevant parts
$ mvn package -DskipTests
```
Alternatively, you can also edit the existing bundle and manually add the `akka-cluster-tls.jks` file to the root of the zip archive. You will also need to set `akka.remote.artery.transport=tls-tcp` at runtime. 

#### Private key

In production, we highly recommend using AWS Secrets manager to provide the node private key. This is done by setting `eclair.front.priv-key-provider=aws-sm`. Default secret name is "node-priv-key", but it is configurable with `eclair.front.aws-sm.priv-key-name`

#### Configuration

We recommend using Beanstalk environment variables for `AKKA_TLS_PASSWORD`, `BACKEND_IP`, and `NODE_PUB_KEY`. Other configuration keys should be set in the `AKKA_CONF` environment variable, semicolon separated. Example:
- `AKKA_CONF`: `eclair.enable-kamon=true; akka.remote.artery.transport=tls-tcp; eclair.front.priv-key-provider=aws-sm...`
- `AKKA_TLS_PASSWORD`: `xxxxxxxx`
- `BACKEND_IP`: `1.2.3.4`
- `NODE_PUB_KEY`: `03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134`

Port `25520` needs to be open, within the Beanstalk security group, and between the Beanstalk security group and your `backend` node.

### Tor

We recommend running your Tor hidden service on a separate server, and use `eclair.tor.targets` to redirect clearnet(*) connections to your `frontend` servers.

(*) Clearnet for Tor, but BOLT 8 encrypted.

Here is the resulting architecture:

```
                             +---+     +-----------+
                             |   |     | +-------+ |
                             |   |-----|-|       | |
       P                -----| L |-----|-| FRONT |-|---,
       U                     | O |-----|-|       | |    \
       B                  ---| A |     | +-------+ |     \
       L                     | D |     |           |      \
       I                     |   |     | +-------+ |       \ +------+
       C              -------| B |-----|-|       | |        `|      |
                             | A |-----|-| FRONT |-|---------| BACK |<-- channels management
       N                     | L |-----|-|       | |        ,|      |    relay logic
       E                 ----| A |     | +-------+ |       / +------+
       T                     | N |     |           |      /
       W      +-------+      | C |     | +-------+ |     /
       O      |       |      | E |-----|-|       | |    /
       R   ---|  Tor  |------| R |-----|-| FRONT |-|---'
       K      |       |      |   |-----|-|       |<------- connection management
              +-------+      |   |     | +-------+ |       routing table sync
                             +---+     +-----------+
```

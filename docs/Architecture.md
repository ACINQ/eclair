# Architecture of the eclair codebase

Eclair is developed in [Scala](https://www.scala-lang.org/) and relies heavily on [Akka](https://akka.io/).
Akka is an [actor programming](https://doc.akka.io/docs/akka/current/typed/guide/actors-intro.html?language=scala) framework similar to [Erlang](https://www.erlang.org/) for the JVM.

The actor model provides a clean separation between components, allowing eclair to:

1. Isolate faults and ensure high availability
2. Scale across CPUs and machines efficiently
3. Simplify development and testing

At a high-level, almost every entity is a separate, sandboxed actor:

- Every peer connection is an actor instance
- Every lightning channel is an actor instance
- Every payment attempt is an actor instance

Some actors are long-lived (e.g. lightning channels) while others are very short-lived (e.g. payment attempts).

## Top-level projects

Eclair is split into three top-level projects:

- `eclair-core`: core library implementing lightning
- `eclair-node`: server daemon built upon `eclair-core` (exposes a Json RPC and WebSocket endpoint)
- `eclair-front`: when using cluster mode, front-end server daemons handling peer connections

The entry point for `eclair-core` is in `Setup.scala`, where we start the actor system, connect to `bitcoind` and create top-level actors.

## Actor system overview

Here is a high-level view of the hierarchy of some of the main actors in the system:

```ascii
                                      +---------+
                            +-------->| Channel |
                            |         +---------+
                        +------+      +---------+
      +---------------->| Peer |----->| Channel |
      |                 +------+      +---------+
      |                     |         +---------+
      |                     +-------->| Channel |
      |                               +---------+
+-------------+
| Switchboard |
+-------------+
      |                               +---------+
      |                     +-------->| Channel |
      |                     |         +---------+
      |                 +------+      +---------+
      +---------------->| Peer |----->| Channel |
                        +------+      +---------+
                            |         +---------+
                            +-------->| Channel |
                                      +---------+

                        +----------------+
      +---------------->| ChannelRelayer |
      |                 +----------------+
+---------+
| Relayer |
+---------+
      |                 +-------------+
      +---------------->| NodeRelayer |
                        +-------------+

                                                           +------------------+
                                              +----------->| PaymentLifecycle |
                                              |            +------------------+
                        +---------------------------+      +------------------+
      +---------------->| MultiPartPaymentLifecycle |----->| PaymentLifecycle |
      |                 +---------------------------+      +------------------+
      |                                       |            +------------------+
      |                                       +----------->| PaymentLifecycle |
+------------------+                                       +------------------+
| PaymentInitiator |                          
+------------------+                                       +------------------+
      |                                       +----------->| PaymentLifecycle |
      |                                       |            +------------------+
      |                 +---------------------------+      +------------------+
      +---------------->| MultiPartPaymentLifecycle |----->| PaymentLifecycle |
                        +---------------------------+      +------------------+
                                              |            +------------------+
                                              +----------->| PaymentLifecycle |
                                                           +------------------+

                        +---------------------+
      +---------------->| MultiPartPaymentFSM |
      |                 +---------------------+
+----------------+
| PaymentHandler |
+----------------+
      |                 +---------------------+
      +---------------->| MultiPartPaymentFSM |
                        +---------------------+

+----------+
| Register |
+----------+

+--------+
| Router |
+--------+
```

And a short description of each actor's role:

- Switchboard: creates and deletes peers
- Peer: p2p connection to another lightning node (standard lightning messages described in [Bolt 1](https://github.com/lightning/bolts/blob/master/01-messaging.md))
- Channel: channel with another lightning node ([Bolt 2](https://github.com/lightning/bolts/blob/master/02-peer-protocol.md))
- Register: maps channel IDs to actors (provides a clean boundary between channel and payment components)
- PaymentInitiator: entry point for sending payments
- Relayer: entry point for relaying payments
- PaymentHandler: entry point for receiving payments
- Router: p2p gossip and the network graph ([Bolt 7](https://github.com/lightning/bolts/blob/master/07-routing-gossip.md))

Actors have two ways of communicating:

- direct messages: when actors have a reference to other actors, they can exchange [direct messages](https://doc.akka.io/docs/akka/current/typed/interaction-patterns.html)
- events: actors can emit events to a shared [event stream](https://doc.akka.io/docs/akka/current/event-bus.html), and other actors can register to these events

## Payment scenarios

Let's dive into a few payment scenarios to show which actors are involved.

### Sending a payment

When we send a payment:

- we run a path-finding algorithm (`Router`)
- we split the payment into smaller chunks if [MPP](https://github.com/lightning/bolts/blob/master/04-onion-routing.md#basic-multi-part-payments) is used (`MultiPartPaymentLifecycle`)
- we retry with alternative routes in some failure cases and record failing channels/payments (`PaymentLifecycle`)
- we add HTLCs to some of our channels

```ascii
                                                                     +------------------+                                    +---------+
                                                              +----->| PaymentLifecycle |-----+                       +----->| Channel |
                                                              |      +------------------+     |                       |      +---------+
+------------------+         +---------------------------+    |      +------------------+     |      +----------+     |      +---------+
| PaymentInitiator |-------->| MultiPartPaymentLifecycle |----+----->| PaymentLifecycle |-----+----->| Register |-----+----->| Channel |
+------------------+         +---------------------------+    |      +------------------+     |      +----------+     |      +---------+
                                               |              |      +------------------+     |                       |      +---------+
                                               |              +----->| PaymentLifecycle |-----+                       +----->| Channel |
                                               |                     +------------------+                                    +---------+
                                               |                            |
                                               |                            |
                                               |      +--------+            |
                                               +----->| Router |<-----------+
                                                      +--------+
```

### Receiving a payment

When we receive a payment:

- htlcs are forwarded by channels to the relayer
- a payment handler compares these htlcs to our payments database
- and decides to fail or fulfill them

```ascii
+---------+
| Channel |-----+
+---------+     |
+---------+     |      +---------+      +----------------+      +----------+
| Channel |-----+----->| Relayer |----->| PaymentHandler |----->| Register |
+---------+     |      +---------+      +----------------+      +----------+
+---------+     |
| Channel |-----+
+---------+
```

### Relaying a payment

When we relay a payment:

- htlcs are forwarded by channels to the relayer
- the relayer identifies the type of relay requested and delegates work to a channel relayer or a node relayer
- if a node relayer is used ([trampoline payments](https://github.com/lightning/bolts/pull/829)):
  - incoming htlcs are validated by a payment handler (similar to the flow to receive payments)
  - outgoing htlcs are sent out (similar to the flow to send payments)

```ascii
                                 +----------------+      +----------+      +---------+
                      +--------->| ChannelRelayer |----->| Register |----->| Channel |
                      |          +----------------+      +----------+      +---------+
+---------+      +---------+
| Channel |----->| Relayer |
+---------+      +---------+
                      |          +-------------+      +---------------------------+
                      +--------->| NodeRelayer |----->| MultiPartPaymentLifecycle |
                                 +-------------+      +---------------------------+
                                        ^
                                        |
                                        v
                               +----------------+
                               | PaymentHandler |
                               +----------------+
```

## Channel scenarios

Let's describe some channel operations and see which actors are involved.

### Opening a channel

When we open a channel:

- we exchange messages with our peer
- we use funds from our on-chain bitcoin wallet
- we start watching on-chain transactions to ensure our peer doesn't cheat us

```ascii
+------+      +---------+            +--------+
| Peer |----->| Channel |-----+----->| Wallet |
+------+      +---------+     |      +--------+
    ^              |          |      +---------+
    |              |          +----->| Watcher |
    +--------------+                 +---------+
```

### Closing a channel

When our peer tries to cheat:

- the blockchain watcher notices it and notifies the channel
- the channel publishes on-chain transactions
- and we notify our peer by sending an error message

```ascii
+---------+       +---------+      +------+
| Watcher |<----->| Channel |----->| Peer |
+---------+       +---------+      +------+
```

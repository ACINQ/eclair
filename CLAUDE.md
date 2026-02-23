# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Eclair is a Scala implementation of the Lightning Network specification (BOLTs). It is a payment channel network built on Bitcoin, developed by ACINQ. The codebase uses Scala 2.13 with the Akka actor framework on Java 21.

## Build Commands

```bash
./mvnw package                          # Build all modules with tests
./mvnw package -DskipTests              # Build without tests
./mvnw test                             # Run all tests
./mvnw test -Dsuites=*<TestClassName>   # Run a single test class
./mvnw -T <threads> test                # Run tests with parallelism
./mvnw package -pl eclair-node -am -Dmaven.test.skip=true  # Build only eclair-node
./mvnw clean install -pl eclair-core -am -Dmaven.test.skip=true  # Install eclair-core to local Maven repo
```

Scala compiler flags include `-Werror`, so warnings are treated as errors.

## Module Structure

- **eclair-core**: Core Lightning implementation library. Contains channel state machine, payment logic, routing, crypto, database layer, and protocol wire messages.
- **eclair-node**: Server daemon. Entry point (`Boot.scala`), REST API (`api/`), and plugin system (`Plugin.scala`).
- **eclair-front**: Front-end server for cluster mode deployments, handling peer connections.

## Architecture

Eclair uses an **Akka actor model** where nearly every entity is a separate actor:

- **Switchboard** creates/manages **Peer** actors (one per p2p connection)
- Each **Peer** manages multiple **Channel** actors (one per Lightning channel)
- **Register** maps channel IDs to channel actors
- **Router** handles network graph gossip (BOLT 7) and path-finding
- **PaymentInitiator** orchestrates sending payments via **MultiPartPaymentLifecycle** → **PaymentLifecycle** actors
- **PaymentHandler** manages receiving payments
- **Relayer** delegates forwarding to **ChannelRelayer** (standard) or **NodeRelayer** (trampoline)

The entry point for `eclair-core` is `Setup.scala`, which starts the actor system, connects to bitcoind, and creates top-level actors.

Actors communicate via direct messages or a shared event stream.

## Key Source Paths (eclair-core)

All under `eclair-core/src/main/scala/fr/acinq/eclair/`:

- `channel/` - Channel state machine (BOLT 2)
- `payment/` - Payment send/receive/relay
- `router/` - Network routing and gossip
- `wire/` - Lightning protocol message codecs (scodec-based)
- `blockchain/` - Bitcoin Core integration (RPC, ZMQ)
- `db/` - Database layer (SQLite and PostgreSQL)
- `io/` - Peer connection handling
- `crypto/` - Cryptographic operations

## Testing

- Framework: ScalaTest. Tests are parallelized by suite (not individual tests).
- Keep each test suite under one minute; split into smaller suites if needed.
- Bug fixes should start with a failing test in a separate commit before the fix.
- Tests live alongside sources in `src/test/scala/`.

## Code Style

- IntelliJ default Scala formatting is used (Ctrl+Alt+L to format, Ctrl+Alt+O to optimize imports).
- No hard style rules, but code should be consistent with surrounding code.
- Commit messages use present tense ("Fix bug" not "Fixed bug"), 50-char summary line.
- Signed commits are required.

## Key Dependencies

- **Akka** 2.6.20 (actors, HTTP, streams, cluster)
- **bitcoin-lib** 0.46 (ACINQ's Bitcoin library, Kotlin-based)
- **scodec** (binary protocol encoding/decoding for wire messages)
- **json4s** (JSON serialization)
- **Kamon** (metrics/monitoring)
- **SQLite** (default DB) / **PostgreSQL** (production option with HikariCP pooling)

# Using Eclair to manage your Bitcoin Core wallet's private keys

You can configure Eclair to control (and never expose) the private keys of your Bitcoin Core wallet. This feature was designed to take advantage of deployment where your Eclair node runs in a
"trusted" runtime environment, but is also very useful if your Bitcoin and Eclair nodes run on different machines for example, with a setup for the Bitcoin host that
is less secure than for Eclair (because it is shared among several services for example).

## Configuring Eclair and Bitcoin Core to use a new Eclair-backed bitcoin wallet

Follow these steps to delegate on-chain key management to eclair:

### 1. Generate a BIP39 mnemonic code and passphrase

You can use any BIP39-compatible tool, including most hardware wallets.

### 2. Create an `eclair-signer.conf` configuration file add it to eclair's data directory

A signer configuration file uses the HOCON format that we already use for `eclair.conf` and must include the following options:

 key                      | description                                                                                                                                                                                                                              
--------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
 eclair.signer.wallet     | wallet name                                                                                                                                                                                                                              
 eclair.signer.mnemonics  | BIP39 mnemonic words                                                                                                                                                                                                                     
 eclair.signer.passphrase | passphrase                                                                                                                                                                                                                               
 eclair.signer.timestamp  | wallet creation UNIX timestamp. Bitcoin core will rescan the blockchain from this UNIX timestamp. Set it to the wallet creation timestamp for simplicity, or a later date if you only have recent UTXOs and you know what you are doing. 

This is an example of `eclair-signer.conf` configuration file:

```hocon
{
   eclair {
      signer {
         wallet = "eclair"
         mnemonics = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title"
         passphrase = ""
         timestamp = 1686055705
      }
   }
}
```

### 3. Use Eclair to generate descriptors and import them into a new bitcoin wallet

Restart eclair, without changing `eclair.bitcoind.wallet` (so it uses the default wallet or the previously used bitcoin wallet for existing nodes).

Create a new empty, descriptor-enabled wallet on your new Bitcoin Core node.

:warning: The name must match the one that you set in `eclair-signer.conf` (here we use "eclair")

```shell
$ bitcoin-cli -named createwallet wallet_name=eclair disable_private_keys=true blank=true descriptors=true load_on_startup=true
```

Generate the descriptors with your Eclair node and import them into a Bitcoin node with the following commands:

```shell
$ eclair-cli getdescriptors | jq --raw-output -c > descriptors.json
$ cat descriptors.json | xargs -0 bitcoin-cli -rpcwallet=eclair importdescriptors
```

Bitcoin core will import descriptors and rescan the blockchain from the time set in `eclair-signer.conf`.
This can take a long time (if you're moving an old existing node to a new setup for example) and your Bitcoin Core node will not be usable until it's done.

### 4. Configure Eclair to use the wallet you created and restart Eclair

In your `eclair.conf`, set `eclair.bitcoind.wallet` to the name of the wallet in `eclair-signer.conf`, and restart Eclair.

You now have a Bitcoin Core watch-only wallet for which only your Eclair node can sign transactions. This Bitcoin Core wallet can
safely be copied to another Bitcoin Core node to monitor your on-chain funds.

:warning: this means that your Bitcoin Core wallet cannot send funds on its own (since it cannot access private keys to sign transactions).
To send funds on-chain you must use `eclair-cli sendonchain`.

:warning: to backup the private keys of this wallet you must either backup your mnemonic code and passphrase, or backup the `eclair-signer.conf` file in your eclair
directory (default is `~/.eclair`) along with your channels and node seed files.

:warning: You can also initialize a backup on-chain wallet with the same mnemonic code and passphrase (on a hardware wallet for example), but be warned that using them may interfere with your node's operations (for example you may end up
double-spending funding transactions generated by your node).

You can also use `eclair-cli getmasterxpub` to get a BIP32 extended public key that you can import into any compatible Bitcoin wallet
to create a watch-only wallet (Electrum for example) that you can use to monitor your Bitcoin Core balance.

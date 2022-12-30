# Peerswap plugin

This plugin allows implements the PeerSwap protocol: https://github.com/ElementsProject/peerswap-spec/blob/main/peer-protocol.md

Disclaimer: PeerSwap is beta-grade software.

We currently only recommend using PeerSwap with small balances or on signet/testnet

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Build

To build this plugin, run the following command in this directory:

```sh
mvn package
```

## Run

To run eclair with this plugin, start eclair with the following command:

```sh
eclair-node-<version>/bin/eclair-node.sh <path-to-plugin-jar>/peerswap-plugin-<version>.jar
```

## Commands

```sh
eclair-cli swapin --shortChannelId=<short-channel-id>> --amountSat=<amount>
eclair-cli swapout --shortChannelId=<short-channel-id>> --amountSat=<amount>
eclair-cli listswaps
eclair-cli swaphistory
eclair-cli cancelswap --swapId=<swap-id>
```

## Persistence

This plugin stores its data into a sqlite database named `peer-swap.sqlite`.
It uses that database to ensure swaps are correctly executed even after a restart of the node.
You can check the status of pending swap by reading directly from that database or using the command `listwaps`.

## Seed

The seed used to generate keys for swaps is stored in the `swap_seed.dat` file. This seed should be backed up and always kept secret.
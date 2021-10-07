## How to perform circular rebalancing

Circular rebalancing is a popular tool for managing liquidity between channels. This document describes an approach to
rebalancing using Eclair.

In this example we assume that there are 4 participants in the Lighting Network: `Alice`, `Bob`, `Carol`, and `Diana`. 

```
            1x1x1
    Alice --------> Bob
      ^              |
4x4x4 |              | 2x2x2
      |              v
    Diana <------- Carol
            3x3x3  
```

`Alice` has two channels `1x1x1` with outbound liquidity of 5M stats, and `4x4x4` with inbound liquidity of 5M sats.
Now `Alice` wants to send some sats from channel `1x1x1` to channel `4x4x4` to be able to receive and forward payments 
via both channels.

First, `Alice` creates an invoice for the desired amount (eg. 1M sats):

```shell
eclair-cli createinvoice --description='circular rebalancing from 1x1x1 ro 4x4x4' \
                         --amountMsat=1000000000
```

Eclair cannot send payments to self using `payinvoice` CLI command. Fortunately, `Alice` can use `sendtoroute` CLI 
command to do so. 

However, in this case `Alice` should provide a valid route from `1x1x1` to `4x4x4`. It's pretty straightforward to 
build a route for our example network: `1x1x1` `->` `2x2x2` `->` `3x3x3` `->` `4x4x4`. `Alice` specifies the route using 
`--shortChannelIds` parameter as a comma separated list of short channel IDs.  

```shell
eclair-cli sendtoroute --shortChannelIds=1x1x1,2x2x2,3x3x3,4x4x4 \
                       --amountMsat=1000000000 \
                       --invoice=<serialized invoice>
```

This command will send 1M sats from channel `1x1x1` to channel `4x4x4`.

In real life its not always easy to find the most economically viable route manually, but Eclair is here to help.
Similarly to `payinvoice`, `findroute` CLI command cannot find routes to self. But `Alice` can use a little trick with 
`findroutebetweennodes`, which allows finding routes between arbitrary nodes. 

To rebalance channels `1x1x1` and `4x4x4`, `Alice` wants to find a route from `Bob` (as a source node) to `Diana` (as a 
target node). In our example there's at least one route from `Bob` to `Diana` via `Alice`, and in real life there can be 
many more such routes, bacause `Alice` can have way more than two channels, so `Alice`'s node should be excluded from 
path-finding using `--ignoreNodeIds` parameter:

```shell
eclair-cli findroutebetweennodes --sourceNodeId=<Bob`s node ID> \
                                 --targetNodeId=<Diana`s node ID> \
                                 --ignoreNodeIds=<Alice`s node ID`> \
                                 --format=shortChannelId
```

Then `Alice` simply appends the outgoing channel ID to the beginning of the found route and the incoming channel ID to 
the end: `1x1x1,<found route>,4x4x4`. In our example the found route is `2x2x2,3x3x3`, so the full route will be 
`1x1x1,2x2x2,3x3x3,4x4x4`. `Alice` can use this route with `sendtoroute` command to perform rebalancing. 


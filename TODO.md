# Zero-fee commitments

- `max_accepted_htlcs` cannot be greater than 114 (verify this with `bitcoind`)
- set `dust_limit` in `reference.conf` to a higher value than previous versions of `eclair`:
- need to update `bitcoin-kmp` / `bitcoin-lib` with p2a script?
- never send `update_fee` for v3, sends back a `warning` if receive one
- when local commit is published, need a transaction to claim the shared anchor with external input
- when remote commit is published, we never need one:
  - it only makes sense to get this commit confirmed if we have either a main output or HTLC outputs
  - in that case we use them directly to spend the shared anchor
- don't force-close when receiving `error`: let the other node do it and bump their commit
  - except for mobile wallets (which will be a dedicated channel type)  
- test that `open_channel` sets `commit_feerate` to `0`
- test that `availableForSend/Receive` can use the whole balance
- mutual-close: add option to ask for a v3 transaction?

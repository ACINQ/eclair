# Zero-fee commitments

- never send `update_fee` for v3, sends back a `warning` if receive one
- test that `open_channel` sets `commit_feerate` to `0`
- test that `availableForSend/Receive` can use the whole balance
- reject `max_accepted_htlcs` greater than 114

- rename `ClaimRemoteDelayed` in dedicated commit and explain why we don't use a wallet static point (splice-upgrade cannot, will spend anyway to pay anchor fees, otherwise never pay commit fees so fine)
- set `dust_limit` in `reference.conf` to a higher value than previous versions of `eclair`

- when local commit is published, need a transaction to claim the shared anchor with external input
- when remote commit is published, we never need one:
  - it only makes sense to get this commit confirmed if we have either a main output or HTLC outputs
  - in that case we use them directly to spend the shared anchor
- don't force-close when receiving `error`: let the other node do it and bump their commit
  - except for mobile wallets (which will be a dedicated channel type)  

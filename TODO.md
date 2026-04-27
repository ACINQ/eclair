# TODO

- finish the `GossipTimestampFilter` mechanism
- gossip queries use block height: verify nothing missing from the spec
- re-read whole spec PR to check if we've missed something

## Refactoring PRs

- Replace announcement messages (`announcement_signatures`, `channel_announcement`, `node_announcement`, `channel_update`) by traits
  - and rename the existing ones with a `V1` suffix
- Update DB to store the abstract message (to handle both v1 and v2)
- Update codecs to store the abstract message as well
  - we need to store both v1 and v2 channel announcements in channel data
  - but our local channel update can easily be translated and re-signed between v1 and v2, only need to store one version
- Check how router handles using a trait instead of concrete classes (everywhere) and verify v2 contains all the necessary data

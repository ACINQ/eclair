Add more splice RBF reconnection tests

We add more tests around disconnection in the middle of signing an RBF
attempt, and verify more details of the `channel_reestablish` message
sent on reconnection.

## PostgreSQL Configuration

By default, Eclair stores its data on the machine's local file system (typically in `~/.eclair` directory) using SQLite.

It also supports PostgreSQL version 10.6 and higher as a database backend. 

To enable PostgreSQL support set the `driver` parameter to `postgres`:

```
eclair.db.driver = postgres
```

### Connection settings

To configure the connection settings use the `database`, `host`, `port` `username` and `password` parameters:

```
eclair.db.postgres.database = "mydb"
eclair.db.postgres.host = "127.0.0.1"      # Default: "localhost"
eclair.db.postgres.port = 12345            # Default: 5432
eclair.db.postgres.username = "myuser"
eclair.db.postgres.password = "mypassword"
```

Eclair uses Hikari connection pool (https://github.com/brettwooldridge/HikariCP) which has a lot of configuration 
parameters. Some of them can be set in Eclair config file. The most important is `pool.max-size`, it defines the maximum 
allowed number of simultaneous connections to the database. 

A good rule of thumb is to set `pool.max-size` to the CPU core count times 2. 
See https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing for better estimation. 

```    
eclair.db.postgres.pool {
    max-size = 8                    # Default: 10
    connection-timeout = 10 seconds # Default: 30 seconds
    idle-timeout = 1 minute         # Default: 10 minutes
    max-life-time = 15 minutes      # Default: 30 minutes
}
```

### Locking settings
 
Running multiple Eclair processes connected to the same database can lead to data corruption and loss of funds. 
That's why Eclair supports database locking mechanisms to prevent multiple Eclair instances from accessing one database together. 

Use `postgres.lock-type` parameter to set the locking schemes.

 Lock type | Description 
---|---
`lease` | At the beginning, Eclair acquires a lease for the database that expires after some time. Then it constantly extends the lease. On each lease extension and each database transaction, Eclair checks if the lease belongs to the Eclair instance. If it doesn't, Eclair assumes that the database was updated by another Eclair process and terminates. Note that this is just a safeguard feature for Eclair rather than a bulletproof database-wide lock, because third-party applications still have the ability to access the database without honoring this locking scheme.
`none` | No locking at all. Useful for tests. DO NOT USE ON MAINNET! 

```
eclair.db.postgres.lock-type = "none" // Default: "lease"
```

#### Database Lease Settings

There are two main configuration parameters for the lease locking scheme: `lease.interval` and `lease.renew-interval`.
`lease.interval` defines lease validity time. During the lease time no other node can acquire the lock, except the lease holder.
After that time the lease is assumed expired, any node can acquire the lease. So that only one node can update the database 
at a time. Eclair extends the lease every `lease.renew-interval` until terminated.  

```
eclair.db.postgres.lease {
    interval = 30 seconds        // Default: 5 minutes
    renew-interval =  10 seconds // Default: 1 minute
}
```

### Backups and replication

The PostgreSQL driver doesn't support Eclair's built-in online backups. Instead, you should use the tools provided
by PostgreSQL.

#### Backup/Restore

For nodes with infrequent channel updates its easier to use `pg_dump` to perform the task. 

It's important to stop the node to prevent any channel updates while a backup/restore operation is in progress. It makes
sense to back up the database after each channel update, to prevent restoring an outdated channel's state and consequently 
losing the funds associated with that channel.

For more information about backup refer to the official PostgreSQL documentation: https://www.postgresql.org/docs/current/backup.html

#### Replication

For busier nodes it isn't practical to use `pg_dump`. Fortunately, PostgreSQL provides built-in database replication which makes the backup/restore process more seamless.

To set up database replication you need to create a main database, that accepts all changes from the node, and a replica database. 
Once replication is configured, the main database will automatically send all the changes to the replica. 
In case of failure of the main database, the node can be simply reconfigured to use the replica instead of the main database.

PostgreSQL supports [different types of replication](https://www.postgresql.org/docs/current/different-replication-solutions.html). 
The most suitable type for an Eclair node is [synchronous streaming replication](https://www.postgresql.org/docs/current/warm-standby.html#SYNCHRONOUS-REPLICATION), 
because it provides a very important feature, that helps keep the replicated channel's state up to date:  

> When requesting synchronous replication, each commit of a write transaction will wait until confirmation is received that the commit has been written to the write-ahead log on disk of both the primary and standby server.  

Follow the official PostgreSQL high availability documentation for the instructions to set up synchronous streaming replication: https://www.postgresql.org/docs/current/high-availability.html  

### Safeguard to prevent accidental loss of funds due to database misconfiguration

Using Eclair with an outdated version of the database or a database created with another seed might lead to loss of funds.

Every time Eclair starts, it checks if the Postgres database connection settings have changed since the last start. 
If in fact the settings have changed, Eclair stops immediately to prevent potentially dangerous 
but accidental configuration changes to come into effect.

Eclair stores the latest database settings in the `${data-dir}/last_jdbcurl` file, and compares its contents with the database settings from the config file. 

The node operator can force Eclair to accept new database 
connection settings by removing the `last_jdbcurl` file. 

### Migrating from Sqlite to Postgres

Eclair supports migrating your existing node from Sqlite to Postgres. Note that the opposite (from Postgres to Sqlite) is not supported.

:warning: Once you have migrated from Sqlite to Postgres there is no going back!

To migrate from Sqlite to Postgres, follow these steps:
1. Stop Eclair
2. Edit `eclair.conf`
   1. Set `eclair.db.postgres.*` as explained in the section [Connection Settings](#connection-settings).
   2. Set `eclair.db.driver=dual-sqlite-primary`. This will make Eclair use both databases backends. All calls to sqlite will be replicated in postgres.
   3. Set `eclair.db.dual.migrate-on-restart=true`. This will make Eclair migrate the data from Sqlite to Postgres at startup.
   4. Set `eclair.db.dual.compare-on-restart=true`. This will make Eclair compare Sqlite and Postgres at startup. The result of the comparison is displayed in the logs.
3. Delete the file `~/.eclair/last_jdbcurl`. The purpose of this file is to prevent accidental change in the database backend.
4. Start Eclair. You should see in the logs:
   1. `migrating all tables...`
   2. `migration complete`
   3. `comparing all tables...`
   4. `comparison complete identical=true` (NB: if `identical=false`, contact support)
5. Eclair should then finish startup and operate normally. Data has been migrated to Postgres, and Sqlite/Postgres will be maintained in sync going forward.
6. Edit `eclair.conf` and set `eclair.db.dual.migrate-on-restart=false` but do not restart Eclair yet.
7. We recommend that you leave Eclair in dual db mode for a while, to make sure that you don't have issues with your new Postgres database. This a good time to set up [Backups and replication](#backups-and-replication).
8. After some time has passed, restart Eclair. You should see in the logs:
   1. `comparing all tables...`
   2. `comparison complete identical=true` (NB: if `identical=false`, contact support)
9. At this point we have confidence that the Postgres backend works normally, and we are ready to drop Sqlite for good.
10. Edit `eclair.conf`
    1. Set `eclair.db.driver=postgres`
    2. Set `eclair.db.dual.compare-on-restart=false`
11. Restart Eclair. From this moment, you cannot go back to Sqlite! If you try to do so, Eclair will refuse to start.
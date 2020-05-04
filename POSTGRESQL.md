## PostgreSQL Configuration

By default Eclair stores its data on the machine's local file system (typically in `~/.eclair` directory) using SQLite. 

To enable PostgreSQL support set `driver` parameter to `psql`:

```
eclair.db.driver = psql
```

### Connection settings

To configure the connection settings use the `database`, `host`, `port` `username` and `password` parameters:

```
eclair.db.psql.database = "mydb"
eclair.db.psql.host = "127.0.0.1"      # Default: "localhost"
eclair.db.psql.port = 12345            # Default: 5432
eclair.db.psql.username = "myuser"
eclair.db.psql.password = "mypassword"
```

Eclair uses Hikari connection pool (https://github.com/brettwooldridge/HikariCP) which has a lot of configuration 
parameters. Some of them can be set in Eclair config file. The most important is `pool.max-size`, that defines the maximum 
allowed number of simultaneous connections to the database. 

A good rule of thumb is to set `pool.max-size` to the CPU core count times 2. 
See https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing for better estimation. 

```    
eclair.db.psql.pool {
    max-size = 8                    # Default: 10
    connection-timeout = 10 seconds # Default: 30 seconds
    idle-timeout = 1 minute         # Default: 10 minutes
    max-life-time = 15 minutes      # Default: 30 minutes
}
```

### Locking settings
 
Running multiple Eclair processes connected to the same database can lead to data corruption and loss of funds. 
That's why Eclair supports database locking mechanisms to prevent multiple Eclair instances access to one database together. 

Use `psql.lock-type` parameter to set the locking schemes.

 Lock type | Description 
---|---
`ownership-lease` | At the beginning, Eclair acquires a lease for the database that expires after some time. Then it constantly extends the lease. On each lease extension and each database transaction, Eclair checks if the lease belongs to the Eclair instance. If it doesn't, Eclair assumes that the database was updated by another Eclair process and terminates.      
`none` | No locking at all. Useful for tests. DO NOT USE ON MAINNET! 

```
eclair.db.psql.lock-type = "none" // Default: "ownership-lease"
```

#### Ownership Lease Settings

There are two main configuration parameters for the ownership lease locking scheme: `lease-interval` and `lease-renew-interval`.
`lease-interval` defines lease validity time. During the lease time no other node can acquire ownership, except the lease owner.
After that time the lease is assumed expired, any node can acquire the ownership. This is so that only one node can update the database 
at a time. Eclair extends the lease every `lease-renew-interval` until terminated.  

```
eclair.db.psql.ownership-lease {
    lease-interval = 30 seconds        // Deafult: 5 minutes
    lease-renew-interval =  10 seconds // Default: 1 minute
}
```

### Backups and replication

The PostgreSQL driver doesn't support Eclair's built-in online backups. Instead, you should use the tools provided
by PostgreSQL.

#### Backup/Restore

For nodes with infrequent channel updates its easier to use `pg_dump` to perform the task. 

It's important to stop the node to prevent any channel updates while backup/restore operation is in progress. It makes
sense to backup the database after each channel update, to prevent restoring an outdated channel's state and consequently 
losing the funds associated with that channel.

For more information about backup refer to the official PostgreSQL documentation: https://www.postgresql.org/docs/current/backup.html

#### Replication

For busier nodes it's not practical to use `pg_dump`. Fortunately, PostgreSQL provides built-in database replication which makes the backup/restore process more seamless.

To set up database replication you need to create a main database, that accepts all changes from the node, and a replica database. 
Once replication is configured, the main database will automatically send all the changes to the replica. 
In case of failure of the main database, the node can be simply reconfigured to use the replica instead of the main database.

PostgreSQL supports [different types of replication](https://www.postgresql.org/docs/current/different-replication-solutions.html). 
The most suitable type for an Eclair node is [synchronous streaming replication](https://www.postgresql.org/docs/current/warm-standby.html#SYNCHRONOUS-REPLICATION), 
because it provides a very important feature, that helps to keep the replicated channel's state up to date:  

> When requesting synchronous replication, each commit of a write transaction will wait until confirmation is received that the commit has been written to the write-ahead log on disk of both the primary and standby server.  

Follow the official PostgreSQL high availability documentation for the instructions to set up synchronous streaming replication: https://www.postgresql.org/docs/current/high-availability.html  

### Safeguard to prevent accidental loss of funds due to database misconfiguration

Using Eclair with an outdated version of the database or a database created with another seed might lead to loss of funds.

Every time Eclair starts, it checks if the Postgres database connection settings were changed since the last start. 
If in fact the settings were changed, Eclair stops immediately to prevent potentially dangerous 
but accidental configuration changes to come into effect.

Eclair stores the latest database settings in the `${data-dir}/jdbc_url` file, and compares its contents with the database settings from the config file. 

The node operator can force Eclair to accept new database 
connection settings by removing the `jdbc_url` file. 
    
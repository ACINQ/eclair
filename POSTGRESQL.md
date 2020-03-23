## PostgreSQL Configuration

To enable PostgreSQL support set `driver` parameter to `psql`:

```
eclair.db.driver = psql
```

### Connection settings

To configure the connection settings use the `database`, `host`, `port` `username` and `password` parameters:

```
eclair.db.psql.database = "mydb"
eclair.db.psql.host = "127.0.0.1"      // Default: "localhost"
eclair.db.psql.port = 12345            // Default: 5432
eclair.db.psql.username = "myuser"
eclair.db.psql.password = "mypassword"
```

Elcair uses Hikari connection pool (https://github.com/brettwooldridge/HikariCP) which has a lot of configuration 
parameters. Some of them can be set in Eclair config file. The most important is `pool.max-size`, that defines the maximum 
allowed number of simultaneous connections to the database. 

A good rule of thumb is to set `pool.max-size` to the CPU core count times 2. 
See https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing for better estimation. 

```    
eclair.db.psql.pool {
    max-size = 8                    // Default: 10
    connection-timeout = 10 seconds // Default: 30 seconds
    idle-timeout = 1 minute         // Default: 10 minutes
    max-life-time = 15 minutes      // Default: 30 minutes
}
```

### Locking settings

Even though PostgreSQL allows concurrent data modifications, Eclair's database access layer is not designed for that. 
Running multiple Eclair processes connected to the same database can lead to data corruption and loss of funds. 
That's why Eclair supports database locking mechanisms to prevent multiple Eclair instances access to one database together. 

Use `psql.lock-type` parameter to set the locking schemes.

 Lock type | Description 
---|---
`optimistic` | In this case Eclair maintains data version both in the database and in the memory. It compares the database and in-memory data versions on each database transaction. If they are different, Eclair assumes that the database was updated by another Eclair process and terminates. Note, that eclair maintains a single data version for the whole database, so this locking scheme can be a bottleneck for busy nodes. The advantage of this locking scheme is that it doesn't require additional configuration.       
`ownership-lease` | At the beginning, Eclair acquires a lease for the database that expires after some time. Then it constantly extends the lease. On each lease extension and each database transaction, Eclair checks if the lease belongs to the Eclair instance. If it doesn't, Eclair assumes that the database was updated by another Eclair process and terminates.      
`none` | No locking at all. Useful for tests. DO NOT USE ON MAINNET! 

```
eclair.db.psql.lock-type = "optimistic" // Default: "ownership-lease"
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


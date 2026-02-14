# LiTD
Lichess Tournament Director

**Need:** Swiss tournaments on Lichess assume that all players will be online simultaneously at the beginning of each round. For players with varying schedules and in different time zones this is challenging, if not practically impossible, and necessitates manual pairing. 

**Goal:** Create a system for automatically managing long-running Swiss tournaments on Lichess.org. 

## Development

### Run the service

```bash
sbt run
```

Set your Atlas connection settings before starting the app:

```bash
export LITD_DB_CONNECT_STRING='mongodb+srv://<user>:<password>@<cluster>/?retryWrites=true&w=majority'
export LITD_DB_DBNAME='litd'
```

The health endpoint should respond with `ok`:

```bash
curl http://localhost:8080/health
```

References:
* [Lichess Feedback - Clocks start automatically in Swiss Tournament?!](https://lichess.org/forum/redirect/post/L9boi7eP)
* [Lichess Feedback - starting timer should be removed#3](https://lichess.org/forum/redirect/post/AvLmQzeD)

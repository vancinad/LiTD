# LiTD
Lichess Tournament Director

**Need:** Swiss tournaments on Lichess assume that all players will be online simultaneously at the beginning of each round. For players with varying schedules and in different time zones this is challenging, if not practically impossible, and necessitates manual pairing. 

**Goal:** Create a system for automatically managing long-running Swiss tournaments on Lichess.org. 

## Development

### Start MongoDB replica set

```bash
docker compose up -d
```

### Run the service

```bash
sbt run
```

The health endpoint should respond with `ok`:

```bash
curl http://localhost:8080/health
```

References:
* [Lichess Feedback - Clocks start automatically in Swiss Tournament?!](https://lichess.org/forum/redirect/post/L9boi7eP)
* [Lichess Feedback - starting timer should be removed#3](https://lichess.org/forum/redirect/post/AvLmQzeD)

# VTL game-mod protocol, v2

How VTL Core (the mod, formerly VTL TierTagger) talks to the VTL Discord bot. The bot side is
`bot/lib/modGateway.js` plus the "Game mod gateway" section of `bot/index.js`;
`111/mock-server/` runs the same gateway code with simulated queues.

## Endpoints

The gateway runs inside the bot process on `127.0.0.1:3056`. The website (Next.js)
forwards `https://www.tierslist.net/mod/` to it with a rewrite in `next.config.ts`,
WebSocket included. Use the `www` host: `tierslist.net`
answers with a redirect, which WebSocket handshakes and the link request do not
follow (the mod treats a redirect like a 404 and waits 10 minutes).

| Method | Path          | Auth            | Purpose                              |
|--------|---------------|-----------------|--------------------------------------|
| GET    | `/mod/health` | -               | `{ ok, protocol: 2, enabled }`       |
| POST   | `/mod/link`   | -               | link code -> token                   |
| POST   | `/mod/unlink` | `Bearer <token>`| revoke this token                    |
| GET    | `/mod/ws?v=2` | optional Bearer | the WebSocket                        |

Every request sends `X-VTL-Client: VTL-Core/<mod> mc/<game>` (`VTL-TierTagger/...` from VTL Mod 3.2.0 and older).
From VTL Core 1.0.0 every WebSocket also sends `X-VTL-Player: <uuid>`, the game's Minecraft UUID.

## Linking

1. On Discord: `/vtl link` (`/tiertagger link` before bot V40). The bot answers (only visible to the player)
   with a code: 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, valid
   10 minutes, single use.
2. In game: `/vtl link <code>` (or the Link screen) posts to `/mod/link` (bot V39):

   ```json
   { "code": "<code>", "player": { "name": "<game name>", "uuid": "<game uuid>" }, "proof": { "nonce": "<16 bytes base64url>" } }
   ```

   Case and dashes in the code are ignored. `proof` is `null` without a premium session.
   With one, the game first calls Mojang `joinServer` with `serverId` = lowercase SHA-1 hex of
   `vtl-link:<code>:<nonce>`, and the bot checks it with Mojang `hasJoined`. The access token
   only goes to Mojang.
3. The bot checks, in this order, and answers the first failure:
   - the Discord account is verified;
   - `player.name` is the verified IGN (case ignored);
   - for a Premium account, Mojang confirms the name and UUID;
   - the network did not link another Discord account in the last 30 days (unless staff gave an
     exemption).
   A refused code stays usable for its 10 minutes.
4. `200 { token, discordId, discordName, name, premiumVerified }`. The token is 32 random bytes
   (base64url). The bot stores only its SHA-256, with the game name, UUID and a keyed hash of
   the network (never the IP).

One token per Discord account: linking again revokes the previous install
(its socket is closed with 4001).

| Answer | Body `error`     | When                                       |
|--------|------------------|--------------------------------------------|
| 400    | `invalid_code`   | unknown, used or expired code              |
| 400    | `bad_request`    | body is not valid link JSON                |
| 400    | `update_required`| body without `player` (VTL Mod 3.2.0 and older) |
| 400    | `not_verified`   | the Discord account is not verified        |
| 400    | `name_mismatch`  | the game runs another name than the verified IGN |
| 400    | `premium_required` | Premium account without a proof          |
| 400    | `premium_failed` | Mojang did not confirm the name and UUID   |
| 409    | `ip_in_use`      | the network linked another Discord account in the last 30 days |
| 503    | `verify_unavailable` | Mojang did not answer                  |
| 429    | `rate_limited`   | more than 10 attempts per IP in 10 minutes |
| 503    | `disabled`       | staff turned the gateway off               |

Every error body also has a `message` meant for the player.

Unlinking: `/vtl unlink` on Discord, or in game (the mod forgets the
token, reconnects anonymously, then posts `/mod/unlink` with the token).

## WebSocket

Connect to `/mod/ws?v=2`. With `Authorization: Bearer <token>` the socket is
**linked**; without it the socket is **anonymous**.

- Anonymous sockets receive queue alerts only.
- **Relink (bot V39):** a token only holds for the network, game account and verified name it
  was made with.
  - If the network (IPv4 address, or IPv6 /64) changed, the bot revokes the token and the socket
    continues anonymously. The same happens when `X-VTL-Player` is missing or another UUID, or
    when staff changed the verified IGN.
  - The hello then carries `relink` (`ip_changed`, `account_changed` or `ign_changed`) and
    `relinkMessage`. The mod forgets the token and asks the player to link again.
  - Staff exemptions skip the network rules.
- Linked sockets also get their own status, next up and your turn, and can join
  or leave queues.

Handshake refusals (plain HTTP, no socket):

| Status | Meaning                                  | Mod reaction                          |
|--------|------------------------------------------|---------------------------------------|
| 401    | token unknown or revoked                 | forget the token, continue anonymous  |
| 404    | wrong path / gateway not deployed        | retry after 10 minutes                |
| 429    | too many handshakes or anonymous sockets | retry after at least 60 s             |
| 503    | gateway off or full                      | retry after at least 60 s             |

Every message is one JSON text frame with `type` and `v` (2). Unknown `type`
values must be ignored.

### Queue object

```json
{
  "queueId": "EU:sword",
  "sessionId": "EU:sword:1759650000000",
  "region": "EU",
  "mode": "sword",
  "modeName": "Sword",
  "openedAt": 1759650000000,
  "testers": ["TesterBot"],
  "waiting": 3,
  "canJoin": true,
  "me": { "status": "waiting", "position": 2 }
}
```

- `queueId` is `<region>:<mode>`. The bot runs one queue per region and kit, so
  several can be open at once.
- `sessionId` changes on every opening. Deduplicate alerts on it.
- `region` is one of `AS_ME`, `AU_OC`, `NA_SA`, `EU`.
- `mode` is one of `vanilla`, `nethop`, `sword`, `mace`, `uhc`, `smp`, `axe`,
  `pot` (the bot's crystal, neth, axeshield and diapot are renamed).
- `canJoin` (linked only, else `null`) says whether region and waitlist role
  allow this player in. The bot still re-checks everything on join.
- `me` (linked only) has `status` `none`, `waiting` or `testing`, and
  `position` while waiting.
- `waiting` is the count when the message was sent. Bot V37 and newer send
  `queue_update` whenever the line, the testers or the player's place change.

### Server -> mod

| type           | Fields                                                    | Sent                                  |
|----------------|-----------------------------------------------------------|---------------------------------------|
| `hello`        | `features`, `linked`, `queues[]`; linked adds `discordName`, `verified`, `region`, and `ticket` while one is open (V37) | after connect, and again when staff change the switches |
| `queue_open`   | `queue`                                                   | a queue opens (or reopens)            |
| `queue_close`  | `queueId`, `sessionId`                                    | a queue closes                        |
| `next_up`      | `queueId`, `message`                                      | linked player became position 1       |
| `your_turn`    | `queueId`, `tester`, `ticketUrl`, `message`               | a tester pulled the linked player     |
| `removed`      | `queueId`, `reason`, `message`                            | kicked, queue banned, left on Discord |
| `join_result`  | `requestId`, `ok`, `code`, `message`, `retryAfterMs?`, `queue?` | answer to `join_queue`          |
| `leave_result` | same as `join_result`                                     | answer to `leave_queue`               |
| `error`        | `code`, `message`                                         | malformed request                     |
| `queue_update` | `queue`                                                   | V37: the line, testers or `me` changed |
| `ticket_open`  | `ticket`                                                  | V37: the linked player was pulled     |
| `ticket_message` | `ticketId`, `message`                                   | V37: a new message in the ticket      |
| `ticket_close` | `ticketId`, `reason`, `message`, `result?`                | V37: the ticket ended                 |
| `ticket_history` | `requestId`, `ok`, `ticketId`, `messages[]`, `code?`, `message?` | V37: answer to `ticket_history` |
| `ticket_send_result` | `requestId`, `ok`, `code`, `message`, `ticketId`, `sent?`, `retryAfterMs?` | V37: answer to `ticket_send` |

`features` is `["alerts"]`, or `["alerts","join"]` for a linked socket while
staff allow joining from the game. `ticketUrl` is always
`https://discord.com/channels/<guild>/<channel>`; the mod only makes https
Discord links clickable.

### Mod -> server

```json
{ "type": "join_queue",  "v": 2, "requestId": "a1b2c3d4", "queueId": "EU:sword" }
{ "type": "leave_queue", "v": 2, "requestId": "e5f6a7b8", "queueId": "EU:sword" }
```

`requestId` is 1-64 characters and comes back in the result. One request per
socket every 2 seconds; faster ones get `rate_limited`. Leaving is only possible
while still waiting: once pulled, the ticket is handled on Discord.

Join and leave go through the same code as the Discord buttons, so every rule
(verification, region, waitlist role, blacklist, queue ban, cooldown, capacity,
active ticket) applies. `message` is written for the player; show it as is.

Result codes:

- **Join and leave:** `joined`, `left`, `not_linked`, `join_disabled`,
  `unknown_queue`, `queue_closed`, `not_in_server`, `rate_limited`.
- **Join only:** `not_verified`, `region_mismatch`, `no_waitlist_role`,
  `blacklisted`, `queue_banned` (with `retryAfterMs`), `cooldown`,
  `queue_full`, `in_ticket`, `already_in_queue`.
- **Leave only:** `not_in_queue`, `in_ticket`.
- **Local codes (never sent by the server):** the mod uses `offline`, `timeout`
  and `disconnected` for requests that never got an answer.

### Ticket chat (bot V37)

The test itself happens in a Discord ticket channel. From V37 the player can chat
in it from the game, and every message there reaches the game.

```json
{ "type": "ticket_send",    "v": 2, "requestId": "a1", "ticketId": "1234567890", "text": "play.vtl.vn please" }
{ "type": "ticket_history", "v": 2, "requestId": "a2", "ticketId": "1234567890" }
```

- **Ticket:** `ticketId` (the Discord channel id), `queueId`, `mode`, `modeName`,
  `region`, `testers[]`, `openedAt`, `ticketUrl`.
- **Message:** `id`, `at` (ms), `author` (`name`, `role` = `player`, `tester`,
  `staff` or `bot`, `self`), `text`, `fromGame`, `attachments[]` (`name`, `url`).
  Discord mentions arrive as `@name`, custom emoji as `:name:`; of a bot embed
  only the title and description are sent (never its fields).
- **Close:** `reason` is `result`, `closed` or `skipped`; `result` is
  `{ tier, mode, previousTier }` when the tester posted one. `message` is for the player.
  `previousTier` (bot V38) is the tier before the test, `"NONE"` when there was none; V37 bots
  leave it out, so treat a missing one as unknown.
- Only linked sockets, only the player's own ticket (`not_your_ticket`
  otherwise). `text` is 1-500 characters (`bad_text`). At most 10 messages per
  20 s per account (`rate_limited` with `retryAfterMs`), history once per 2 s.
- A message from the game is posted in the ticket as
  `🎮 **<Discord name>** (Minecraft): <text>` with every ping disabled, and is
  not sent back to the game. In `ticket_history` it comes back as the player's
  own (`fromGame: true`, `self: true`).
- After a reconnect, `hello.ticket` names the open ticket; fetch
  `ticket_history` to fill the chat. A ticket the mod still shows as open but
  the hello does not name has ended meanwhile.
- Other result codes: `ticket_gone`, `send_failed`, `unsupported` (a gateway
  without ticket chat). Local codes: `closed`, `bad_text`, `offline`, `timeout`.
- High Test (bridge) tickets are not covered.

### Close codes

| Code | Meaning                                         | Mod reaction                      |
|------|-------------------------------------------------|-----------------------------------|
| 4001 | token revoked (unlinked, or linked elsewhere)   | forget that token, reconnect anonymous |
| 4002 | replaced by a newer socket of the same account  | stop, tell the player             |
| 4003 | unsupported protocol version                    | stop, ask to update the mod       |
| 4008 | policy (flooding)                               | reconnect after at least 60 s     |
| 1013 | try again later                                 | reconnect after at least 60 s     |
| 1001 | gateway turned off / bot restarting             | reconnect with backoff            |
| any other, including 1008 from a proxy           |                                                 | reconnect with backoff            |

Backoff: 5, 10, 20, 40, then 60 s, plus 0-5 s jitter. It resets after a
connection stays up for 60 s. The server pings every 30 s; the mod treats 75 s
of silence as a dead connection.

## Limits (per client IP)

- Link: 10 attempts per 10 minutes.
- Wrong link codes, all IPs together: 300 per 10 minutes. While that is reached,
  every link attempt gets 429, so guessing codes stays hopeless.
- Handshake: 30 per minute.
- Anonymous sockets: 5 open at once.
- Server-wide: 3000 sockets.
- Messages: 4 KB.
- Ticket chat: 10 messages per 20 s per account, 500 characters each.
- Link body: 1 KB.

The gateway reads the client IP from `CF-Connecting-IP` (set by Cloudflare,
which a client cannot change), then `X-Real-IP`, then `X-Forwarded-For`, and
only when the request came from loopback (the website's proxy).

## Staff switches (bot Admin Panel -> Mode Locks, and the website admin)

| Switch               | Off means                                                    |
|----------------------|--------------------------------------------------------------|
| Game Mod Connection  | every socket closed (1001); new ones and links get 503       |
| Join From Game       | alerts only; `hello.features` drops `join`, requests get `join_disabled` |

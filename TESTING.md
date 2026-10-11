# Testing VTL Core

The mod talks to the VTL bot's game-mod gateway, protocol v2
(`111/MOD-PROTOCOL-v2.md`). For testing without the bot there is a mock in
`111/mock-server/`. It runs the bot's own gateway code (`modGateway.js` is a
copy of `bot/lib/modGateway.js`) and simulates the Discord side.

On Windows, clone with long paths on (the bundled 3D item pack has deep paths):

```bash
git config --global core.longpaths true
```

## 1. Headless checks (no game needed)

The mock needs Node.js 18 or newer. Install its one dependency (`ws`) once:

```bash
npm --prefix 111/mock-server install
```

```bash
./gradlew :1.21.11:configCheck          # vtl.json load/save, broken file, tiertagger.toml import (18)
./gradlew :1.21.11:hubStateCheck        # kit cards, chosen queue, button action (32); also runs
                               # quickJoinCheck (9), textFitCheck (19), ticketLogCheck (29),
                               # animatorCheck (25), tierChangeCheck (15), particleCheck (13)
./gradlew :1.21.11:queueTuningCheck     # reconnect backoff, close codes, dedupe set
```

```bash
node 111/mock-server/mock-queue-server.js      # terminal 1, leave it running
./gradlew :1.21.11:queueProtocolCheck           # terminal 2
```

`queueProtocolCheck` runs the mod's real `QueueClient` against the mock through
every message and close code: alerts, linking, unlinking (from Discord and
from the game), join/leave, next up, your turn, the ticket chat (bot V37: open, messages both ways,
history, live queue updates, result), 4001/4002/4003, 1008 and 404. It takes about 40 seconds and
must end with `QueueProtocolCheck: 53 checks passed`.

## 2. Automatic screenshots (dev client)

```bash
node 111/mock-server/mock-queue-server.js      # terminal 1
./gradlew :<minecraft>:runClient -Pdevshots     # terminal 2, e.g. :26.3:runClient
```

The dev client opens at 1920x1080, walks through every Hub state against the
mock (not linked, settings, players, linked, waiting at GUI scale 2/3/auto,
your turn), makes a flat creative world for the HUD shot, renders the kit card
figures, and quits. Add `-PdevshotsSize=854x480` to shoot a small window instead (the kit
card figures need 1200 px and are skipped there). Shots land in `versions/<minecraft>/run/vtl-shots/screenshots/`.
The run also checks input on that version: the log must show `[devshots] type ok`, `click ok` and `key ok`. It only runs
from the development environment; a released jar ignores it.

The kit card images come from that run:

```bash
node tools/kit-art.js
```

writes `src/main/resources/assets/vtl/textures/gui/kit/<kit>.png` and
`<kit>_gray.png` (128x176). Rebuild after running it.

## 3. Point the mod at the mock

Build (`./gradlew chiseledBuild`) and copy the jar for your game, `build/libs/<mod version>/VTL-Core+mc<minecraft>.jar`, into the
instance's `mods/` folder. Fabric API is required; ukulib is not.

Open the Hub (**R**, `/vtl`, or the VTL button on the title and pause menus),
go to **SETTINGS → Discord**, and set **Server** to
`ws://127.0.0.1:3056/mod/ws`. The link URL follows automatically.

To go back to production, set it to `wss://www.tierslist.net/mod/ws?v=2`
(with `www`: `tierslist.net` redirects there, and the mod does not follow redirects).

## 4. Alerts (not linked)

| Mock console          | Expected in game                                              |
|-----------------------|---------------------------------------------------------------|
| `open sword EU`       | Toast "Sword queue open (EU)" + sound + chat line             |
| `open sword EU` again | Toast again (a reopen is a new session)                       |
| `open mace AS_ME`     | Nothing if the Region Filter is EU                            |
| `close sword EU`      | Nothing in game; `/vtl queues` no longer lists it             |
| `drop 1008`           | Reconnects by itself within ~10 s, no duplicate toast         |
| `gateway off`         | Socket closed; the mod retries quietly every minute           |
| Ctrl+C, restart mock  | The mod reconnects by itself                                  |

`/vtl queues` lists every open queue. When not linked it ends with a hint
to link. `/tiertagger ...` still works for everything `/vtl ...` does.

In the Hub, the **PLAY** tab shows one card per kit. Open kits are in colour,
closed kits are grey, and the big button says **LINK DISCORD** while not linked.

## 5. Link

1. In the mock console type `code`. It prints `/vtl link XXXXXX`.
2. Type that (or `/vtl link XXXXXX`) in game chat, or use
   **SETTINGS → Discord → Link Discord**.
3. Expect "Linked as MockPlayer!". The Hub's top bar shows the linked name.
4. The token is stored in `config/tiertagger-queue-token.txt`, never in
   `config/vtl.json`.

Each code works once and expires after 10 minutes.

## 6. Join, leave, your turn (linked)

MockPlayer is verified in region EU, so EU queues are joinable and others are
not.

| Do                                         | Expected                                                  |
|--------------------------------------------|-----------------------------------------------------------|
| mock: `open sword EU`                      | Toast says "Press J to join"; chat line has a green **[Join]** |
| press **J** within 30 s (no screen open)   | "You joined the Sword queue at position 1."               |
| press **J** after 30 s                     | Nothing                                                   |
| Hub → PLAY → Sword card → **JOIN QUEUE**   | Joined; the card shows "YOU ARE #1", button turns **LEAVE QUEUE** |
| `/vtl leave`                               | "You left the Sword queue."                               |
| mock: `add sword EU`, then join            | Position 2; the Hub shows "1 AHEAD OF YOU" and the wait time |
| mock: `open mace AS_ME`                    | No alert (Only queues I can join is on by default)        |
| `/vtl join mace`                           | "This queue is for AS_ME, but your verified region is EU." |
| mock: `pull sword EU`                      | "YOUR TURN" title + toast; Hub button **OPEN TICKET**     |
| mock: `kick sword EU` (after a new join)   | Gold line "A tester removed you from the Sword queue."    |
| mock: `join off`                           | Hub button says **JOIN ON DISCORD**; `/vtl join` says joining from the game is turned off |
| mock: `unlink`                             | "Your Discord link was removed..."; alerts keep coming    |
| `/vtl unlink` in game                      | "Unlinked..."; alerts keep coming                         |

**J** never joins on its own; it only works after an alert you can join, for
30 seconds, and not while a screen is open.

With two queues open, a bare `/vtl join` lists both with **[Join]**
buttons instead of guessing. Tab completion offers the open modes and regions.

## 7. Ticket chat (bot V37)

Linked, joined and pulled as in section 6 (`pull sword EU`):

| Do                                         | Expected                                                  |
|--------------------------------------------|-----------------------------------------------------------|
| mock: `pull sword EU`                      | Hub: **OPEN TICKET** opens the **TICKET** tab; the bot's ticket message is there |
| mock: `say Which server?` (Hub closed)     | Toast "TesterBot · ticket" + "Press R to reply"; HUD shows "1 new" |
| press **R**                                | The Hub opens straight on TICKET; the message is there    |
| type a line, press Enter                   | It shows at once as "sending…", then as sent; the mock console logs it |
| send 11 lines within 20 s                  | The 11th is marked "not sent" with "sending messages too fast" |
| mock: `result HT3 LT3` (Hub open)          | The result screen opens: the tier rolls for 2.6 s, then **TIER UP!** LT3 → HT3, the figure cheers, fireworks and confetti; CONTINUE goes back |
| mock: `result LT4 HT3` (new ticket)        | **TIER DOWN** HT3 → LT4, the figure hangs its head, rain and a cold vignette |
| mock: `result HT3 NONE`                    | **NEW TIER!** HT3 (a first tier celebrates too) |
| a result while the inventory is open       | Toast "Press R to see your result"; R opens it |
| afterwards, TICKET                         | **TEST COMPLETE · HT3**, typing is off                    |
| mock: `endticket skipped` (new ticket)     | TICKET shows the skip message from the bot                |

The chat stays readable after the ticket closes, until the next ticket opens.

## 8. Hub visuals

- PLAY: the figure wears the kit of the selected card and stands in its guard (sword up,
  shield raised, mace overhead, potion ready, bow drawn...). Picking another card swings into
  the new guard. Drag the figure to turn it; it turns back when let go.
- Sparks rise from the floor, faint crystals drift across, the crystals glow violet/pink.
  **SETTINGS → Hub & HUD → Background effects** turns all of that off.
- Everything moves by real time: it must look the same at 30 and 144 FPS.

## 9. HUD

In a world, while waiting in a queue, the HUD shows `#N` and the wait time in the
corner set in **SETTINGS → Hub & HUD**. It hides with F1 and while any screen
is open.

## 10. 3D item icons

**SETTINGS → Hub & HUD → 3D item icons** turns the bundled
"Block-Accurate: 3D Item Models" resource pack (teyokuma, MIT) on or off. It is
off by default and also appears in Minecraft's Resource Packs screen.

## 11. Two game windows

Linking the same Discord account in two instances is allowed, but only the newer
one stays connected. The older one shows "Queue alerts moved to another game
window..." and stops.

## Troubleshooting

- **Nothing happens on `open`**: run `status` in the mock. If `sockets` is 0,
  the mod never connected. Check the Server URL, that Queue alerts is
  on, and that you edited the right instance.
- **Could not reach the linking server**: `latest.log` has a
  `Queue link POST ...` line with the URL, HTTP status and body.
- **Settings reset after an update**: if `config/vtl.json` could not be read,
  it is moved to `config/vtl.json.broken` and defaults are used.
- **Direct link test (PowerShell)**:
  ```powershell
  Invoke-RestMethod -Uri http://127.0.0.1:3056/mod/link -Method Post -ContentType 'application/json' -Body '{"code":"XXXXXX"}'
  ```

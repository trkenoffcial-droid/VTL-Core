// Mock of the VTL bot's game-mod gateway, protocol v2 (see ../MOD-PROTOCOL-v2.md).
// Lets the mod be developed and tested without the Discord bot.
//
// The networking, linking and limits are the bot's own code: modGateway.js is a copy of
// bot/lib/modGateway.js. Only the queues and the Discord side are simulated here.
//
// Usage:  node mock-queue-server.js [--demo]
//   Gateway  http://127.0.0.1:3056/mod  (PORT overrides)   -> point the mod at ws://127.0.0.1:3056/mod/ws
//   Control  http://127.0.0.1:3057/dev  (DEV_PORT overrides) -> used by QueueProtocolCheck
// Type "help" for the console commands.

const http = require('http');
const readline = require('readline');
const gatewayLib = require('./modGateway');

const PORT = Number(process.env.PORT || 3056);
const DEV_PORT = Number(process.env.DEV_PORT || PORT + 1);
const GUILD_ID = '1000000000000000000';

const REGIONS = ['AS_ME', 'AU_OC', 'NA_SA', 'EU'];
// Same ids and names the bot sends (crystal -> vanilla, neth -> nethop, axeshield -> axe, diapot -> pot).
const MODES = {
  vanilla: 'Crystal', nethop: 'NetheritePot', sword: 'Sword', mace: 'Mace',
  uhc: 'UHC', smp: 'SMP Kit', axe: 'Axe & Shield', pot: 'DiamondPot',
};
// Simulated Discord members. "You" is the account a link code is issued for by default.
const ACCOUNTS = {
  '100000000000000001': { name: 'MockPlayer', region: 'EU', verified: true },
  '100000000000000002': { name: 'OtherPlayer', region: 'EU', verified: true },
};
const YOU = '100000000000000001';
const TESTERS = ['TesterAlpha', 'TesterBeta', 'TesterBot'];

/** "REGION:mode" -> { region, mode, sessionId, openedAt, testers, waiting: [ids], testing: Set } */
const queues = new Map();
let joinFromGame = true;
let gatewayOn = true;
/** ticketId -> { ticketId, userId, region, mode, openedAt, messages: [] } (open tickets only) */
const tickets = new Map();
let nextTicket = 1;
let nextMessage = 1;
let lastPosted = null; // the last game message, as it appeared on "Discord"

const queueId = (region, mode) => `${region}:${mode}`;
const canJoin = (id, q) => Boolean(ACCOUNTS[id]?.verified) && ACCOUNTS[id].region === q.region;

function view(q, id) {
  const v = {
    queueId: queueId(q.region, q.mode), sessionId: q.sessionId, region: q.region, mode: q.mode,
    modeName: MODES[q.mode], openedAt: q.openedAt, testers: q.testers, waiting: q.waiting.length,
    canJoin: id ? canJoin(id, q) : null,
  };
  if (id) {
    const pos = q.waiting.indexOf(id);
    const status = q.testing.has(id) ? 'testing' : pos >= 0 ? 'waiting' : 'none';
    v.me = { status, position: status === 'waiting' ? pos + 1 : null };
  }
  return v;
}

function hello(id) {
  const h = { features: id && joinFromGame ? ['alerts', 'join'] : ['alerts'], linked: Boolean(id), queues: [...queues.values()].map(q => view(q, id)) };
  if (id) Object.assign(h, { discordName: ACCOUNTS[id]?.name || 'Player', verified: Boolean(ACCOUNTS[id]?.verified), region: ACCOUNTS[id]?.region || null });
  const t = id && ticketOf(id);
  if (t) h.ticket = ticketView(t);
  return h;
}

// --- tickets (bot V37) ---
const ticketOf = (id) => [...tickets.values()].find(t => t.userId === id) || null;

function ticketView(t) {
  const q = queues.get(queueId(t.region, t.mode));
  return {
    ticketId: t.ticketId, queueId: queueId(t.region, t.mode), mode: t.mode, modeName: MODES[t.mode], region: t.region,
    testers: q ? q.testers : [], openedAt: t.openedAt, ticketUrl: `https://discord.com/channels/${GUILD_ID}/${t.ticketId}`,
  };
}

/** A message in the ticket: stored, and sent to the player's game unless they typed it there. */
function ticketMessage(t, author, text, fromGame = false) {
  const m = { id: String(3000000000000000000n + BigInt(nextMessage++)), at: Date.now(), author, text, fromGame, attachments: [] };
  t.messages.push(m);
  if (!fromGame) gateway.sendToAccount(t.userId, 'ticket_message', { ticketId: t.ticketId, message: m });
  return m;
}

function endTicket(t, reason, result) {
  tickets.delete(t.ticketId);
  const q = queues.get(queueId(t.region, t.mode));
  if (q) q.testing.delete(t.userId);
  const message = reason === 'result'
    ? (result ? `Test complete: ${result.tier} in ${MODES[t.mode]}.` : 'Test complete.')
    : reason === 'skipped' ? 'Your ticket was skipped by staff. You can join the queue again.' : 'Your ticket was closed.';
  const fields = { ticketId: t.ticketId, reason, message };
  if (result) fields.result = result;
  gateway.sendToAccount(t.userId, 'ticket_close', fields);
  if (q) queueChanged(q);
  log(`ticket ${t.ticketId} of ${ACCOUNTS[t.userId]?.name} ended: ${reason}`);
}

/** queue_update for every connection: the line, the testers or someone's place changed. */
function queueChanged(q) {
  gateway.broadcast('queue_update', (discordId) => ({ queue: view(q, discordId) }));
}

async function handleTicket(id, { type, ticketId, text }) {
  const t = tickets.get(ticketId);
  if (!t || t.userId !== id) return { ok: false, code: 'not_your_ticket', message: 'This ticket is not open for you any more.' };
  if (type === 'ticket_history') return { ok: true, code: 'history', message: '', messages: t.messages.slice(-50) };
  const name = ACCOUNTS[id]?.name || 'Player';
  lastPosted = { content: `\u{1F3AE} **${name}** (Minecraft): ${text}`, allowedMentions: { parse: [] } };
  const sent = ticketMessage(t, { name, role: 'player', self: true }, text, true);
  log(`${name} in ticket ${ticketId}: ${text}`);
  return { ok: true, code: 'sent', message: 'Sent.', sent };
}

async function handleRequest(id, { type, queueId: qid }) {
  if (!joinFromGame) return { ok: false, code: 'join_disabled', message: 'Joining from the game is turned off right now. Use the queue on Discord.' };
  const q = queues.get(qid);
  if (!q) {
    const [region, mode] = String(qid).split(':');
    return REGIONS.includes(region) && MODES[mode]
      ? { ok: false, code: 'queue_closed', message: 'This queue is closed.' }
      : { ok: false, code: 'unknown_queue', message: 'Unknown queue.' };
  }
  const name = MODES[q.mode];
  if (type === 'leave_queue') {
    if (q.testing.has(id)) return { ok: false, code: 'in_ticket', message: 'Your test has already started. Cancel it in your ticket on Discord.' };
    if (!q.waiting.includes(id)) return { ok: false, code: 'not_in_queue', message: `You are not in the ${name} queue.` };
    q.waiting = q.waiting.filter(x => x !== id);
    log(`${ACCOUNTS[id]?.name} left ${qid}`);
    announceNextUp(q);
    setImmediate(() => queueChanged(q));
    return { ok: true, code: 'left', message: `You left the ${name} queue.`, queue: view(q, id) };
  }
  if (!ACCOUNTS[id]?.verified) return { ok: false, code: 'not_verified', message: 'Verify your account on Discord before joining a queue.' };
  if (!canJoin(id, q)) return { ok: false, code: 'region_mismatch', message: `This queue is for ${q.region}, but your verified region is ${ACCOUNTS[id].region}.` };
  if (q.testing.has(id)) return { ok: false, code: 'in_ticket', message: 'Your test has already started. Use your ticket on Discord.' };
  if (q.waiting.includes(id)) return { ok: false, code: 'already_in_queue', message: `You are already in the ${name} queue.` };
  q.waiting.push(id);
  const position = q.waiting.length;
  log(`${ACCOUNTS[id]?.name} joined ${qid} at #${position}`);
  setImmediate(() => { announceNextUp(q); queueChanged(q); });
  return { ok: true, code: 'joined', message: `You joined the ${name} queue at position ${position}.`, queue: view(q, id) };
}

function announceNextUp(q) {
  const first = q.waiting[0];
  if (first && q.lastNextUp !== first) {
    q.lastNextUp = first;
    gateway.sendToAccount(first, 'next_up', { queueId: queueId(q.region, q.mode), message: `You are next in the ${MODES[q.mode]} queue. Get ready!` });
  }
}

// --- the Discord side, simulated ---------------------------------------------------------

// V39: set by the relink-ip dev action - the next connections come from another network.
let ipOverride = null;

const actions = {
  // name (optional): call the account like the dev client's player, so a V39 link by name passes
  code({ discordId = YOU, name = null } = {}) {
    if (!ACCOUNTS[discordId]) throw new Error('unknown account');
    if (name) ACCOUNTS[discordId].name = String(name);
    const { code, expiresAt } = linkStore.issueCode(discordId);
    log(`link code for ${ACCOUNTS[discordId].name}: ${code} (expires in ${Math.round((expiresAt - Date.now()) / 60000)} min) -> /tiertagger link ${code}`);
    return { code };
  },
  open({ mode = 'sword', region = 'EU' } = {}) {
    if (!MODES[mode] || !REGIONS.includes(region)) throw new Error('unknown mode or region');
    const id = queueId(region, mode);
    if (queues.has(id)) actions.close({ mode, region });
    const now = Date.now();
    const q = { region, mode, sessionId: `${id}:${now}`, openedAt: now, testers: [TESTERS[Math.floor(Math.random() * TESTERS.length)]], waiting: [], testing: new Set(), lastNextUp: null };
    queues.set(id, q);
    gateway.broadcast('queue_open', (discordId) => ({ queue: view(q, discordId) }));
    log(`opened ${id}`);
    return { queueId: id, sessionId: q.sessionId };
  },
  close({ mode = 'sword', region = 'EU' } = {}) {
    const id = queueId(region, mode);
    const q = queues.get(id);
    if (!q) return { closed: false };
    queues.delete(id);
    gateway.broadcast('queue_close', () => ({ queueId: id, sessionId: q.sessionId }));
    log(`closed ${id}`);
    return { closed: true };
  },
  pull({ mode = 'sword', region = 'EU', discordId = YOU } = {}) {
    const id = queueId(region, mode);
    const q = queues.get(id);
    if (!q || !q.waiting.includes(discordId)) throw new Error('that player is not waiting in that queue');
    q.waiting = q.waiting.filter(x => x !== discordId);
    q.testing.add(discordId);
    const t = { ticketId: String(2000000000000000000n + BigInt(nextTicket++)), userId: discordId, region, mode, openedAt: Date.now(), messages: [] };
    tickets.set(t.ticketId, t);
    gateway.sendToAccount(discordId, 'your_turn', {
      queueId: id, tester: q.testers[0], ticketUrl: `https://discord.com/channels/${GUILD_ID}/${t.ticketId}`,
      message: `It's your turn for ${MODES[mode]}! Open your ticket on Discord.`,
    });
    gateway.sendToAccount(discordId, 'ticket_open', { ticket: ticketView(t) });
    const who = ACCOUNTS[discordId]?.name || 'Player';
    ticketMessage(t, { name: 'VTL', role: 'bot', self: false },
      `@${who}\nTest Ticket - ${MODES[mode]}\nHello @${who}, please start chatting with @${q.testers[0]} here!`);
    log(`pulled ${who} from ${id} -> ticket ${t.ticketId}`);
    announceNextUp(q);
    queueChanged(q);
    return { pulled: true, ticketId: t.ticketId };
  },
  kick({ mode = 'sword', region = 'EU', discordId = YOU } = {}) {
    const q = queues.get(queueId(region, mode));
    if (!q || !q.waiting.includes(discordId)) throw new Error('that player is not waiting in that queue');
    q.waiting = q.waiting.filter(x => x !== discordId);
    gateway.sendToAccount(discordId, 'removed', { queueId: queueId(region, mode), reason: 'kicked', message: `A tester removed you from the ${MODES[mode]} queue.` });
    announceNextUp(q);
    queueChanged(q);
    return { kicked: true };
  },
  unlink({ discordId = YOU } = {}) {
    const removed = linkStore.revokeAccount(discordId);
    gateway.closeAccount(discordId, 4001, 'Unlinked');
    log(`unlinked ${ACCOUNTS[discordId]?.name}: ${removed}`);
    return { unlinked: removed };
  },
  /** Puts another (simulated) account in line, e.g. so MockPlayer ends up #2. */
  add({ mode = 'sword', region = 'EU', discordId = '100000000000000002' } = {}) {
    const q = queues.get(queueId(region, mode));
    if (!q) throw new Error('that queue is not open');
    if (!q.waiting.includes(discordId)) q.waiting.push(discordId);
    announceNextUp(q);
    queueChanged(q);
    log(`${ACCOUNTS[discordId]?.name || discordId} waits in ${queueId(region, mode)} at #${q.waiting.indexOf(discordId) + 1}`);
    return { position: q.waiting.indexOf(discordId) + 1 };
  },
  /** The tester writes in MockPlayer's ticket on "Discord". */
  say({ text = 'Which server do you want to play on?', discordId = YOU } = {}) {
    const t = ticketOf(discordId);
    if (!t) throw new Error('that player has no open ticket');
    const q = queues.get(queueId(t.region, t.mode));
    const m = ticketMessage(t, { name: q?.testers?.[0] || 'Tester', role: 'tester', self: false }, String(text));
    return { said: m.id };
  },
  /** The last message the game posted, as it appeared on "Discord". */
  ticket({ discordId = YOU } = {}) {
    const t = ticketOf(discordId);
    const parse = lastPosted?.allowedMentions?.parse;
    return { open: Boolean(t), ticketId: t?.ticketId || null, last: lastPosted?.content || null, mentionsBlocked: Array.isArray(parse) && parse.length === 0 };
  },
  /** /result: the ticket closes with a tier (tier "NONE" closes without one); previous is the tier before (V38). */
  result({ tier = 'HT3', previous = 'LT3', discordId = YOU } = {}) {
    const t = ticketOf(discordId);
    if (!t) throw new Error('that player has no open ticket');
    ticketMessage(t, { name: 'VTL', role: 'bot', self: false }, 'Test completed successfully! This ticket will be closed in 5 seconds.');
    endTicket(t, 'result', String(tier).toUpperCase() === 'NONE' ? null : { tier: String(tier), mode: t.mode, previousTier: String(previous).toUpperCase() });
    return { closed: true };
  },
  /** The Close (or Skip) button on the ticket. */
  endticket({ reason = 'closed', discordId = YOU } = {}) {
    const t = ticketOf(discordId);
    if (!t) throw new Error('that player has no open ticket');
    endTicket(t, reason === 'skipped' ? 'skipped' : 'closed');
    return { closed: true };
  },
  /** Closes every socket with `code`, e.g. 1008 or 1013, to test the mod's reaction. */
  drop({ code = 1001 } = {}) {
    gateway.closeAll(Number(code), 'Dropped by the mock');
    return { dropped: true };
  },
  join({ on = !joinFromGame } = {}) {
    joinFromGame = Boolean(on);
    gateway.broadcast('hello', (discordId) => hello(discordId));
    log(`join from game: ${joinFromGame ? 'on' : 'off'}`);
    return { joinFromGame };
  },
  gateway({ on = !gatewayOn } = {}) {
    gatewayOn = Boolean(on);
    if (!gatewayOn) gateway.closeAll(1001, 'Turned off by staff');
    log(`gateway: ${gatewayOn ? 'on' : 'off'}`);
    return { gateway: gatewayOn };
  },
  'relink-ip'() {
    ipOverride = '203.0.113.99';
    log(`connections now come from ${ipOverride} (a linked game will be asked to link again)`);
    return { ok: true, ip: ipOverride };
  },
  status() {
    const s = { ...gateway.stats(), joinFromGame, gateway: gatewayOn, queues: [...queues.values()].map(q => ({ queueId: queueId(q.region, q.mode), waiting: q.waiting.map(id => ACCOUNTS[id]?.name), testing: [...q.testing].map(id => ACCOUNTS[id]?.name) })) };
    return s;
  },
};

// --- wiring -------------------------------------------------------------------------------

function log(line) {
  console.log(`[mock] ${line}`);
}

const linkStore = gatewayLib.createLinkStore({ load: () => null, save: () => {} });
const gateway = gatewayLib.createModGateway({
  port: PORT, host: '127.0.0.1', linkStore, isEnabled: () => gatewayOn,
  buildHello: hello, handleRequest, handleTicket, discordName: (id) => ACCOUNTS[id]?.name || null,
  // V39 identity check, like a cracked account on the bot: the game name must be the account name.
  verifyLink: async ({ discordId, player }) => {
    const ign = ACCOUNTS[discordId]?.name;
    if (!ign) return { ok: false, status: 400, error: 'not_verified', message: 'Verify on Discord first with /verify, then link again.' };
    if (player.name.toLowerCase() !== ign.toLowerCase()) {
      return {
        ok: false, status: 400, error: 'name_mismatch',
        message: `This game is logged in as ${player.name}, but your Discord account is verified as ${ign}. Log in to Minecraft as ${ign} and link again.`,
      };
    }
    return { ok: true, premiumVerified: false };
  },
  currentIgn: (id) => ACCOUNTS[id]?.name || null,
  clientIpOf: (req) => ipOverride || gatewayLib.clientIp(req),
  log, warn: (line) => console.warn(`[mock] ${line}`),
});

// Control API for QueueProtocolCheck: POST /dev/<action> with a JSON body. Loopback only.
const dev = http.createServer((req, res) => {
  const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)); };
  const name = new URL(req.url, 'http://localhost').pathname.replace(/^\/dev\//, '');
  if (req.method !== 'POST' || !Object.hasOwn(actions, name)) return send(404, { error: 'not_found' });
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    try { send(200, actions[name](raw ? JSON.parse(raw) : {})); } catch (e) { send(400, { error: e.message }); }
  });
});

const HELP = `Commands:
  code [discordId]            issue a link code (like /tiertagger link on Discord)
  open <mode> <region>        open a queue          e.g. open sword EU
  close <mode> <region>       close it
  pull <mode> <region>        pull MockPlayer -> your_turn
  add <mode> <region>         put OtherPlayer in line (so MockPlayer is #2)
  kick <mode> <region>        kick MockPlayer -> removed
  say <text...>               the tester writes in MockPlayer's ticket
  result [tier] [previous]    /result: the ticket closes with that tier (default HT3, from LT3)
  endticket [skipped]         the Close (or Skip) button on the ticket
  unlink                      revoke MockPlayer's link (4001)
  drop <closeCode>            close every socket with that code (1008, 1013, 4002...)
  join on|off                 Join From Game switch
  gateway on|off              the whole gateway (503 when off)
  status                      sockets and queues
modes: ${Object.keys(MODES).join(' ')}   regions: ${REGIONS.join(' ')}`;

function runConsole(line) {
  const [cmd, a, b] = line.trim().split(/\s+/);
  if (!cmd) return;
  try {
    const out = {
      help: () => console.log(HELP),
      code: () => actions.code(a ? { discordId: a } : {}),
      open: () => actions.open({ mode: a, region: (b || 'EU').toUpperCase() }),
      close: () => actions.close({ mode: a, region: (b || 'EU').toUpperCase() }),
      pull: () => actions.pull({ mode: a, region: (b || 'EU').toUpperCase() }),
      add: () => actions.add({ mode: a, region: (b || 'EU').toUpperCase() }),
      kick: () => actions.kick({ mode: a, region: (b || 'EU').toUpperCase() }),
      say: () => actions.say({ text: line.trim().slice(4) || undefined }),
      result: () => actions.result({ tier: a || 'HT3', previous: b || 'LT3' }),
      endticket: () => actions.endticket({ reason: a }),
      unlink: () => actions.unlink(),
      drop: () => actions.drop({ code: a || 1001 }),
      join: () => actions.join({ on: a !== 'off' }),
      gateway: () => actions.gateway({ on: a !== 'off' }),
      status: () => console.log(JSON.stringify(actions.status(), null, 2)),
    }[cmd];
    if (!out) return console.log('Unknown command. Type "help".');
    out();
  } catch (e) {
    console.log(`error: ${e.message}`);
  }
}

(async () => {
  if (!(await gateway.listen())) process.exit(1);
  dev.listen(DEV_PORT, '127.0.0.1', () => log(`control API on http://127.0.0.1:${DEV_PORT}/dev`));
  console.log(HELP);
  readline.createInterface({ input: process.stdin }).on('line', runConsole);

  if (process.argv.includes('--demo')) {
    // Cycle through every kind of event so the in-game side can be watched without typing.
    const steps = [
      () => actions.open({ mode: 'sword', region: 'EU' }),
      () => actions.open({ mode: 'mace', region: 'AS_ME' }),
      () => actions.code(),
      () => actions.close({ mode: 'mace', region: 'AS_ME' }),
      () => actions.close({ mode: 'sword', region: 'EU' }),
    ];
    let i = 0;
    setInterval(() => { try { steps[i++ % steps.length](); } catch (e) { log(e.message); } }, 20_000).unref();
  }
})();

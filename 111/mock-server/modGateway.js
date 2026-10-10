/**
 * Game-mod gateway: the HTTP + WebSocket endpoint the VTL TierTagger mod talks
 * to (protocol in MOD-PROTOCOL-v2.md). This module only does networking,
 * linking and connection bookkeeping. Every Discord-side decision - what a
 * queue looks like to an account, whether a join is allowed - is made by the
 * callbacks index.js passes in.
 *
 * Security model
 *   - Listens on 127.0.0.1 only; the website (Next.js) proxies /mod/ to it.
 *   - Alerts are public information, so an anonymous socket is allowed. It can
 *     only receive queue open/close events.
 *   - Everything personal (hello details, join/leave, your turn) needs a token.
 *     Tokens come from a one-time code issued on Discord (/tiertagger link), are 256-bit
 *     random, and only their SHA-256 is stored. One active token per account.
 *   - Per-IP limits on link attempts, handshakes and anonymous sockets, a global
 *     socket cap, a 4 KB message cap and a minimum gap between requests.
 *   - Ticket chat (V37): only linked accounts, at most 10 messages per 20 s and
 *     500 characters each; index.js checks that the ticket is the player's own.
 *   - Failed link attempts are also capped across all IPs, so guessing codes stays
 *     hopeless even for someone who can fake their IP.
 */
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PROTOCOL_VERSION = 2;
const HEARTBEAT_MS = 30_000;
const MAX_MESSAGE_BYTES = 4096;
const MAX_BODY_BYTES = 1024;
const REQUEST_GAP_MS = 2000;
const HISTORY_GAP_MS = 2000;
const MAX_CHAT_CHARS = 500;
const CODE_TTL_MS = 10 * 60_000;
const CODE_LENGTH = 6;
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; // no 0/O/1/I/L
const STORE_VERSION = 2;
const NETWORK_HISTORY_MS = 30 * 24 * 3600 * 1000; // one network links one Discord account this long
/** Why a linked game was signed out when it connected (sent in its hello as relink/relinkMessage). */
const RELINK_MESSAGES = {
  ip_changed: 'Your network changed, so the game was signed out. Run /tiertagger link on Discord and link again.',
  account_changed: 'This game is logged in to a different Minecraft account than the one you linked. Link again.',
  ign_changed: 'Your verified Minecraft name changed. Link again.',
};
const LIMITS = {
  link: { max: 10, windowMs: 10 * 60_000 },     // link attempts per IP
  linkFailures: { max: 300, windowMs: 10 * 60_000 }, // wrong codes, all IPs together
  handshake: { max: 30, windowMs: 60_000 },     // socket handshakes per IP
  chat: { max: 10, windowMs: 20_000 },          // ticket messages per account
  anonymousPerIp: 5,                            // open anonymous sockets per IP
  totalSockets: 3000,
};

// --- small pure helpers -------------------------------------------------------
const hashToken = (token) => crypto.createHash('sha256').update(String(token)).digest('hex');

function newLinkCode() {
  let code = '';
  for (let i = 0; i < CODE_LENGTH; i++) code += CODE_ALPHABET[crypto.randomInt(CODE_ALPHABET.length)];
  return code;
}

/** Case-insensitive; spaces and dashes are ignored. */
function normalizeCode(raw) {
  return String(raw || '').toUpperCase().replace(/[\s-]/g, '');
}

/**
 * The network an address belongs to: an IPv4 address as is, an IPv6 address by its /64
 * (computers rotate the rest of it every day), "::ffff:a.b.c.d" as IPv4. Null if not an IP.
 */
function networkOf(ip) {
  const raw = String(ip || '').trim().replace(/%.*$/, ''); // drop an IPv6 zone id
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i.exec(raw);
  if (mapped) return net.isIPv4(mapped[1]) ? mapped[1] : null;
  if (net.isIPv4(raw)) return raw;
  if (!net.isIPv6(raw)) return null;
  // A trailing dotted quad fills the last two groups; only the first four matter here.
  const v6 = raw.toLowerCase().replace(/\d{1,3}(?:\.\d{1,3}){3}$/, '0:0');
  const [head, tail] = v6.split('::');
  const left = head ? head.split(':') : [];
  const right = tail === undefined ? [] : tail ? tail.split(':') : [];
  const groups = tail === undefined ? left : [...left, ...Array(8 - left.length - right.length).fill('0'), ...right];
  return groups.slice(0, 4).map((g) => g.padStart(4, '0')).join(':');
}

/** The Mojang session "serverId" a link proof is made for: SHA-1 hex of the code and the mod's nonce. */
function linkServerId(code, nonce) {
  return crypto.createHash('sha1').update(`vtl-link:${normalizeCode(code)}:${String(nonce || '')}`).digest('hex');
}

/** The game profile in a link request: { name, uuid } with a valid name and a normalized uuid, or null. */
function parseLinkPlayer(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const name = typeof raw.name === 'string' && /^[A-Za-z0-9_]{3,16}$/.test(raw.name) ? raw.name : null;
  const uuid = normalizeUuid(raw.uuid);
  return name && uuid ? { name, uuid } : null;
}

/** The proof part of a link request: null (none), { nonce }, or undefined when malformed. */
function parseLinkProof(raw) {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== 'object' || typeof raw.nonce !== 'string' || !/^[A-Za-z0-9_-]{16,64}$/.test(raw.nonce)) return undefined;
  return { nonce: raw.nonce };
}

/** Minecraft UUID as 32 lowercase hex characters, or null. */
function normalizeUuid(raw) {
  const hex = String(raw || '').toLowerCase().replace(/-/g, '');
  return /^[0-9a-f]{32}$/.test(hex) ? hex : null;
}

/** Sliding-window counter per key. */
class RateWindow {
  constructor({ max, windowMs }) {
    this.max = max;
    this.windowMs = windowMs;
    this.hits = new Map();
  }
  /** Like hit(), without counting. */
  peek(key, now = Date.now()) {
    const recent = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    return recent.length >= this.max ? { ok: false, retryAfterMs: this.windowMs - (now - recent[0]) } : { ok: true };
  }
  hit(key, now = Date.now()) {
    const recent = (this.hits.get(key) || []).filter((t) => now - t < this.windowMs);
    if (recent.length >= this.max) {
      this.hits.set(key, recent);
      return { ok: false, retryAfterMs: this.windowMs - (now - recent[0]) };
    }
    recent.push(now);
    this.hits.set(key, recent);
    return { ok: true };
  }
  prune(now = Date.now()) {
    for (const [key, list] of this.hits) {
      const recent = list.filter((t) => now - t < this.windowMs);
      if (recent.length) this.hits.set(key, recent); else this.hits.delete(key);
    }
  }
}

/**
 * The visitor's IP. The gateway only listens on loopback, so every request
 * comes through the website's proxy and the real address is in its headers.
 * CF-Connecting-IP comes first: Cloudflare sets it on every request and a
 * client cannot change it, while X-Real-IP passes through Cloudflare as the
 * client sent it. (A front nginx that sets X-Real-IP clears CF-Connecting-IP.)
 */
function clientIp(req) {
  const remote = req.socket?.remoteAddress || '';
  const loopback = remote === '127.0.0.1' || remote === '::1' || remote === '::ffff:127.0.0.1';
  if (loopback) {
    const forwarded = req.headers['cf-connecting-ip'] || req.headers['x-real-ip'] ||
      String(req.headers['x-forwarded-for'] || '').split(',')[0];
    const ip = String(forwarded || '').trim();
    if (ip) return ip;
  }
  return remote;
}

function validRequestId(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 64 ? value : null;
}

function bearerToken(req) {
  const match = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
  return match ? match[1] : null;
}

// --- link store ---------------------------------------------------------------
/**
 * One-time link codes (memory only - they live 10 minutes) and tokens
 * (persisted through `load`/`save`, stored as SHA-256 only).
 * Stored shape (version 2):
 *   { version: 2, salt,
 *     tokens:  { [sha256]: { discordId, createdAt, lastSeenAt, name, uuid, premiumVerified, ipKey, v: 2 } },
 *     history: { [ipKey]: [{ discordId, at }] },   // who linked from a network, last 30 days
 *     exempt:  { [discordId]: true } }             // staff let this account share networks
 * Networks are kept only as `ipKey` (an HMAC of the network with this store's salt), never as IPs.
 * A version 1 file (tokens without an identity) is cleared on load: everyone links again.
 */
function createLinkStore({ load, save, now = () => Date.now() }) {
  let migratedCount = 0;
  const data = (() => {
    const raw = load();
    const out = raw && typeof raw === 'object' ? raw : {};
    let changed = false;
    if (out.version !== STORE_VERSION) {
      migratedCount = out.tokens && typeof out.tokens === 'object' ? Object.keys(out.tokens).length : 0;
      out.tokens = {};
      out.history = {};
      out.version = STORE_VERSION;
      changed = true;
    }
    if (typeof out.salt !== 'string' || !/^[0-9a-f]{64}$/.test(out.salt)) {
      out.salt = crypto.randomBytes(32).toString('hex');
      changed = true;
    }
    if (!out.tokens || typeof out.tokens !== 'object') out.tokens = {};
    if (!out.history || typeof out.history !== 'object') out.history = {};
    if (!out.exempt || typeof out.exempt !== 'object') out.exempt = {};
    if (changed) save(out);
    return out;
  })();
  const codes = new Map(); // code -> { discordId, expiresAt }

  function dropExpiredCodes() {
    const t = now();
    for (const [code, entry] of codes) if (entry.expiresAt <= t) codes.delete(code);
  }

  /** Drops history older than NETWORK_HISTORY_MS. Returns true when something was removed. */
  function pruneHistory() {
    const cutoff = now() - NETWORK_HISTORY_MS;
    let changed = false;
    for (const [key, list] of Object.entries(data.history)) {
      const kept = (Array.isArray(list) ? list : []).filter((h) => h && h.at > cutoff);
      if (kept.length !== (list || []).length) changed = true;
      if (kept.length) data.history[key] = kept; else delete data.history[key];
    }
    return changed;
  }

  function touch(entry) {
    // Written at most once an hour per token to keep disk writes low.
    if (now() - (entry.lastSeenAt || 0) > 3_600_000) {
      entry.lastSeenAt = now();
      save(data);
    }
  }

  return {
    /** Tokens cleared because the file was from before V39. */
    migratedCount,
    /** The network of `ip` as a 16-hex key; equal for the same IPv4 address or IPv6 /64. */
    ipKey(ip) {
      const network = networkOf(ip) || `raw:${String(ip || '')}`;
      return crypto.createHmac('sha256', data.salt).update(network).digest('hex').slice(0, 16);
    },
    /** A fresh code for this account; it replaces the account's unused code. */
    issueCode(discordId) {
      dropExpiredCodes();
      for (const [code, entry] of codes) if (entry.discordId === discordId) codes.delete(code);
      let code;
      do { code = newLinkCode(); } while (codes.has(code));
      codes.set(code, { discordId, expiresAt: now() + CODE_TTL_MS });
      return { code, expiresAt: now() + CODE_TTL_MS };
    },
    /** The account a code belongs to, without using it up; null if unknown or expired. */
    peekCode(rawCode) {
      dropExpiredCodes();
      return codes.get(normalizeCode(rawCode))?.discordId || null;
    },
    /**
     * Single use. `identity` = { name, uuid, premiumVerified, ipKey } of the game that linked.
     * Returns { token, discordId } or null. Revokes the account's older token and records the
     * network in the history.
     */
    redeemCode(rawCode, identity) {
      dropExpiredCodes();
      const code = normalizeCode(rawCode);
      const entry = codes.get(code);
      if (!entry || !identity) return null;
      codes.delete(code);
      this.revokeAccount(entry.discordId, { silent: true });
      const token = crypto.randomBytes(32).toString('base64url');
      const t = now();
      data.tokens[hashToken(token)] = {
        discordId: entry.discordId, createdAt: t, lastSeenAt: t,
        name: String(identity.name || ''), uuid: normalizeUuid(identity.uuid),
        premiumVerified: Boolean(identity.premiumVerified), ipKey: identity.ipKey || null, v: STORE_VERSION,
      };
      pruneHistory();
      if (identity.ipKey) {
        const list = (data.history[identity.ipKey] || []).filter((h) => h.discordId !== entry.discordId);
        list.push({ discordId: entry.discordId, at: t });
        data.history[identity.ipKey] = list;
      }
      save(data);
      return { token, discordId: entry.discordId };
    },
    /**
     * True when another Discord account linked from this network in the last 30 days and
     * neither account has a staff exemption.
     */
    networkTaken(discordId, ipKey) {
      if (pruneHistory()) save(data);
      if (data.exempt[discordId]) return false;
      return (data.history[ipKey] || []).some((h) => h.discordId !== discordId && !data.exempt[h.discordId]);
    },
    /** The stored entry behind a token, or null. */
    lookup(token) {
      if (!token) return null;
      const entry = data.tokens[hashToken(token)];
      if (!entry) return null;
      touch(entry);
      return entry;
    },
    /** The account behind a token, or null. */
    resolve(token) {
      return this.lookup(token)?.discordId || null;
    },
    isExempt(discordId) {
      return Boolean(data.exempt[discordId]);
    },
    setExempt(discordId, on) {
      if (on) data.exempt[discordId] = true; else delete data.exempt[discordId];
      save(data);
    },
    /** Revokes every token of an account. Returns how many were removed. */
    revokeAccount(discordId, { silent = false } = {}) {
      let removed = 0;
      for (const [hash, entry] of Object.entries(data.tokens)) {
        if (entry.discordId === discordId) { delete data.tokens[hash]; removed++; }
      }
      if (removed && !silent) save(data);
      return removed;
    },
    /** Revokes one token. Returns the account it belonged to, or null. */
    revokeToken(token) {
      const hash = hashToken(token);
      const entry = data.tokens[hash];
      if (!entry) return null;
      delete data.tokens[hash];
      save(data);
      return entry.discordId;
    },
    isLinked(discordId) {
      return Object.values(data.tokens).some((e) => e.discordId === discordId);
    },
    /** The stored entry of an account's link (name, uuid, premiumVerified, createdAt...), or null. */
    accountEntry(discordId) {
      return Object.values(data.tokens).find((e) => e.discordId === discordId) || null;
    },
    linkedCount() {
      return new Set(Object.values(data.tokens).map((e) => e.discordId)).size;
    },
  };
}

// --- gateway --------------------------------------------------------------------
/**
 * options:
 *   port, host               where to listen (host defaults to 127.0.0.1)
 *   linkStore                from createLinkStore
 *   isEnabled()              false = refuse everything (admin switch)
 *   buildHello(discordId|null)            -> hello fields (sync)
 *   handleRequest(discordId, msg)         -> Promise<result fields> (join/leave)
 *   handleTicket(discordId, msg)          -> Promise<result fields> (ticket_send/ticket_history; optional)
 *   discordName(discordId)                -> display name for /link answers
 *   verifyLink({ discordId, player: { name, uuid }, proof: { serverId } | null })
 *                                         -> Promise<{ ok: true, premiumVerified } | { ok: false, status, error, message }>
 *                                            (V39: is this game allowed to link that account? Without it no link succeeds.)
 *   currentIgn(discordId)                 -> the verified IGN now, or null (V39: a renamed or unverified account relinks)
 *   clientIpOf(req)                       -> the visitor's IP (default clientIp; the mock overrides it)
 *   log(line), warn(line)
 */
function createModGateway(options) {
  const {
    port, host = '127.0.0.1', linkStore, isEnabled = () => true,
    buildHello, handleRequest, handleTicket = null, discordName = () => null, verifyLink = null,
    currentIgn = null, clientIpOf = clientIp,
    log = () => {}, warn = () => {}, limits = {},
  } = options;

  const linkLimiter = new RateWindow(limits.link || LIMITS.link);
  const linkFailures = new RateWindow(limits.linkFailures || LIMITS.linkFailures);
  const handshakeLimiter = new RateWindow(LIMITS.handshake);
  const chatLimiter = new RateWindow(limits.chat || LIMITS.chat);
  const anonymousPerIp = new Map();     // ip -> count
  const byAccount = new Map();          // discordId -> ws
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });

  const send = (ws, type, fields = {}) => {
    if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify({ type, v: PROTOCOL_VERSION, ...fields }));
  };
  const json = (res, status, body) => {
    const payload = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload), 'Cache-Control': 'no-store' });
    res.end(payload);
  };
  const disabledBody = { error: 'disabled', message: 'The queue connection is turned off right now.' };

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size <= MAX_BODY_BYTES) chunks.push(chunk);
      });
      req.on('end', () => (size > MAX_BODY_BYTES ? reject(new Error('body too large')) : resolve(Buffer.concat(chunks).toString('utf8'))));
      req.on('error', reject);
    });
  }

  async function handleHttp(req, res) {
    const { pathname } = new URL(req.url, 'http://localhost');
    try {
      if (req.method === 'GET' && pathname === '/mod/health') {
        return json(res, 200, { ok: true, protocol: PROTOCOL_VERSION, enabled: Boolean(isEnabled()), ticketChat: typeof handleTicket === 'function' });
      }
      if (req.method === 'POST' && pathname === '/mod/link') {
        if (!isEnabled()) return json(res, 503, disabledBody);
        const ip = clientIpOf(req);
        const limit = linkFailures.peek('all').ok ? linkLimiter.hit(ip) : linkFailures.peek('all');
        if (!limit.ok) {
          return json(res, 429, { error: 'rate_limited', message: 'Too many attempts. Try again in a few minutes.', retryAfterMs: limit.retryAfterMs });
        }
        let body = null;
        try { body = JSON.parse(await readBody(req)); } catch { return json(res, 400, { error: 'bad_request', message: 'Invalid request.' }); }
        if (!body || typeof body !== 'object') return json(res, 400, { error: 'bad_request', message: 'Invalid request.' });
        // Mods before VTL Core 3.3.0 send only the code: they cannot prove who is playing.
        if (body.player === undefined) return json(res, 400, { error: 'update_required', message: 'Update to VTL Core 3.3.0 to link this game.' });
        const player = parseLinkPlayer(body.player);
        const proof = parseLinkProof(body.proof);
        if (!player || proof === undefined) return json(res, 400, { error: 'bad_request', message: 'Invalid request.' });

        // The code is only used up once every check passed, so a player who fixes the
        // problem (logs in with the right account) can try again with the same code.
        const discordId = linkStore.peekCode(body.code);
        if (!discordId) {
          linkFailures.hit('all');
          return json(res, 400, { error: 'invalid_code', message: 'This code is invalid or has expired. Run /tiertagger link on Discord to get a new one.' });
        }
        const ipKey = linkStore.ipKey(ip);
        if (linkStore.networkTaken(discordId, ipKey)) {
          return json(res, 409, { error: 'ip_in_use', message: 'This network is already linked to another Discord account. Ask staff if you share a network.' });
        }
        const unavailable = { error: 'verify_unavailable', message: "Minecraft's login servers did not answer. Try again in a few minutes." };
        if (typeof verifyLink !== 'function') return json(res, 503, unavailable);
        let verdict;
        try {
          verdict = await verifyLink({ discordId, player, proof: proof ? { serverId: linkServerId(body.code, proof.nonce) } : null });
        } catch (err) {
          warn(`[MOD] link check failed for ${discordId}: ${err?.message || err}`);
          return json(res, 503, unavailable);
        }
        if (!verdict || verdict.ok !== true) {
          const status = Number.isInteger(verdict?.status) && verdict.status >= 400 && verdict.status < 600 ? verdict.status : 400;
          return json(res, status, { error: verdict?.error || 'link_refused', message: verdict?.message || 'This game could not be linked.' });
        }
        // Checked again after the await: another account may have linked from this network
        // while the identity check ran. Nothing runs between this check and redeemCode.
        if (linkStore.networkTaken(discordId, ipKey)) {
          return json(res, 409, { error: 'ip_in_use', message: 'This network is already linked to another Discord account. Ask staff if you share a network.' });
        }
        const premiumVerified = Boolean(verdict.premiumVerified);
        const result = linkStore.redeemCode(body.code, { ...player, premiumVerified, ipKey });
        if (!result) {
          return json(res, 400, { error: 'invalid_code', message: 'This code is invalid or has expired. Run /tiertagger link on Discord to get a new one.' });
        }
        // The account may have had an older install connected: that token is gone now.
        closeAccount(result.discordId, 4001, 'Linked on another install');
        log(`[MOD] ${result.discordId} linked a game install (${premiumVerified ? 'premium, verified by Mojang' : 'name only'}).`);
        return json(res, 200, { token: result.token, discordId: result.discordId, discordName: discordName(result.discordId), name: player.name, premiumVerified });
      }
      if (req.method === 'POST' && pathname === '/mod/unlink') {
        const discordId = linkStore.revokeToken(bearerToken(req));
        if (!discordId) return json(res, 401, { error: 'invalid_token', message: 'Not linked.' });
        closeAccount(discordId, 4001, 'Unlinked');
        log(`[MOD] ${discordId} unlinked a game install.`);
        return json(res, 200, { ok: true });
      }
      return json(res, 404, { error: 'not_found', message: 'Not found.' });
    } catch (err) {
      warn(`[MOD] ${req.method} ${pathname} failed: ${err?.message || err}`);
      try { json(res, 500, { error: 'internal_error', message: 'Something went wrong. Please try again.' }); } catch (_) {}
    }
  }

  function refuse(socket, status, text) {
    try {
      socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    } catch (_) {}
    socket.destroy();
  }

  function handleUpgrade(req, socket, head) {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/mod/ws') return refuse(socket, 404, 'Not Found');
    if (!isEnabled()) return refuse(socket, 503, 'Service Unavailable');
    const ip = clientIpOf(req);
    if (!handshakeLimiter.hit(ip).ok) return refuse(socket, 429, 'Too Many Requests');
    if (wss.clients.size >= LIMITS.totalSockets) return refuse(socket, 503, 'Service Unavailable');

    const token = bearerToken(req);
    let discordId = null;
    let relink = null;
    if (token) {
      const entry = linkStore.lookup(token);
      if (!entry) return refuse(socket, 401, 'Unauthorized');
      relink = relinkReason(entry, req, ip);
      if (relink) {
        // The link only holds for the network, game account and verified name it was made
        // with. The game continues anonymously and is told why, so the player links again.
        linkStore.revokeToken(token);
        log(`[MOD] ${entry.discordId} was signed out of the game mod (${relink}).`);
      } else {
        discordId = entry.discordId;
      }
    }
    if (!discordId && (anonymousPerIp.get(ip) || 0) >= LIMITS.anonymousPerIp) {
      return refuse(socket, 429, 'Too Many Requests');
    }
    wss.handleUpgrade(req, socket, head, (ws) => onConnection(ws, { discordId, ip, version: url.searchParams.get('v'), relink }));
  }

  /** Null when a linked game may stay linked, else the reason it must link again. */
  function relinkReason(entry, req, ip) {
    if (!linkStore.isExempt(entry.discordId) && entry.ipKey && linkStore.ipKey(ip) !== entry.ipKey) return 'ip_changed';
    const player = normalizeUuid(req.headers['x-vtl-player']);
    if (!player || player !== entry.uuid) return 'account_changed';
    if (typeof currentIgn === 'function') {
      const ign = currentIgn(entry.discordId);
      if (!ign || String(ign).toLowerCase() !== String(entry.name || '').toLowerCase()) return 'ign_changed';
    }
    return null;
  }

  function onConnection(ws, { discordId, ip, version, relink = null }) {
    ws.on('error', () => {});
    if (String(version) !== String(PROTOCOL_VERSION)) {
      return ws.close(4003, 'Unsupported protocol version - update the mod');
    }
    ws.discordId = discordId;
    ws.isAlive = true;
    ws.lastRequestAt = 0;
    if (discordId) {
      const previous = byAccount.get(discordId);
      if (previous) previous.close(4002, 'Replaced by a newer connection');
      byAccount.set(discordId, ws);
    } else {
      ws.ip = ip;
      anonymousPerIp.set(ip, (anonymousPerIp.get(ip) || 0) + 1);
    }

    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('message', (raw, isBinary) => onMessage(ws, raw, isBinary));
    ws.on('close', () => {
      if (ws.discordId && byAccount.get(ws.discordId) === ws) byAccount.delete(ws.discordId);
      if (ws.ip) {
        const left = (anonymousPerIp.get(ws.ip) || 1) - 1;
        if (left <= 0) anonymousPerIp.delete(ws.ip); else anonymousPerIp.set(ws.ip, left);
      }
    });

    try {
      send(ws, 'hello', relink
        ? { ...buildHello(null), relink, relinkMessage: RELINK_MESSAGES[relink] }
        : buildHello(discordId));
    } catch (err) {
      warn(`[MOD] hello failed: ${err?.message || err}`);
      ws.close(1011, 'Server error');
    }
  }

  async function onMessage(ws, raw, isBinary) {
    let msg = null;
    try { msg = JSON.parse(raw.toString('utf8')); } catch (_) {}
    if (isBinary || !msg || typeof msg !== 'object' || typeof msg.type !== 'string') {
      return send(ws, 'error', { code: 'bad_request', message: 'Messages must be JSON objects with a "type".' });
    }
    if (msg.type === 'ticket_send' || msg.type === 'ticket_history') return onTicketMessage(ws, msg);
    const resultType = msg.type === 'join_queue' ? 'join_result' : msg.type === 'leave_queue' ? 'leave_result' : null;
    if (!resultType) return send(ws, 'error', { code: 'unknown_type', message: `Unknown message type "${String(msg.type).slice(0, 40)}".` });
    const requestId = validRequestId(msg.requestId);
    if (!requestId || typeof msg.queueId !== 'string' || msg.queueId.length > 64) {
      return send(ws, 'error', { code: 'bad_request', message: 'requestId (1-64 chars) and queueId are required.' });
    }
    if (!ws.discordId) {
      return send(ws, resultType, { requestId, ok: false, code: 'not_linked', message: 'Link your Discord account first: run /tiertagger link on Discord.' });
    }
    const now = Date.now();
    if (now - ws.lastRequestAt < REQUEST_GAP_MS) {
      return send(ws, resultType, { requestId, ok: false, code: 'rate_limited', message: 'Slow down and try again in a moment.', retryAfterMs: REQUEST_GAP_MS - (now - ws.lastRequestAt) });
    }
    ws.lastRequestAt = now;
    let result;
    try {
      result = await handleRequest(ws.discordId, { type: msg.type, queueId: msg.queueId });
    } catch (err) {
      warn(`[MOD] ${msg.type} failed for ${ws.discordId}: ${err?.message || err}`);
      result = { ok: false, code: 'internal_error', message: 'Something went wrong. Please try again.' };
    }
    send(ws, resultType, { requestId, ...result });
  }

  /** ticket_send / ticket_history: chat with the player's own Discord ticket. */
  async function onTicketMessage(ws, msg) {
    const resultType = msg.type === 'ticket_send' ? 'ticket_send_result' : 'ticket_history';
    const requestId = validRequestId(msg.requestId);
    if (!requestId || typeof msg.ticketId !== 'string' || msg.ticketId.length === 0 || msg.ticketId.length > 64) {
      return send(ws, 'error', { code: 'bad_request', message: 'requestId (1-64 chars) and ticketId are required.' });
    }
    const answer = (fields) => send(ws, resultType, { requestId, ticketId: msg.ticketId, ...fields });
    if (!ws.discordId) {
      return answer({ ok: false, code: 'not_linked', message: 'Link your Discord account first: run /tiertagger link on Discord.' });
    }
    if (typeof handleTicket !== 'function') {
      return answer({ ok: false, code: 'unsupported', message: 'Ticket chat is not available on this server.' });
    }
    const request = { type: msg.type, ticketId: msg.ticketId };
    const now = Date.now();
    if (msg.type === 'ticket_send') {
      const text = typeof msg.text === 'string' ? msg.text.trim() : '';
      if (!text || text.length > MAX_CHAT_CHARS) {
        return answer({ ok: false, code: 'bad_text', message: `Messages must be 1-${MAX_CHAT_CHARS} characters.` });
      }
      const limit = chatLimiter.hit(ws.discordId, now);
      if (!limit.ok) {
        return answer({ ok: false, code: 'rate_limited', message: 'You are sending messages too fast. Wait a moment.', retryAfterMs: limit.retryAfterMs });
      }
      request.text = text;
    } else {
      if (now - (ws.lastHistoryAt || 0) < HISTORY_GAP_MS) {
        return answer({ ok: false, code: 'rate_limited', message: 'Slow down and try again in a moment.', retryAfterMs: HISTORY_GAP_MS - (now - ws.lastHistoryAt) });
      }
      ws.lastHistoryAt = now;
    }
    let result;
    try {
      result = await handleTicket(ws.discordId, request);
    } catch (err) {
      warn(`[MOD] ${msg.type} failed for ${ws.discordId}: ${err?.message || err}`);
      result = { ok: false, code: 'internal_error', message: 'Something went wrong. Please try again.' };
    }
    answer(result || { ok: false, code: 'internal_error', message: 'Something went wrong. Please try again.' });
  }

  /** Closes the account's socket (if any). */
  function closeAccount(discordId, code, reason) {
    const ws = byAccount.get(discordId);
    if (ws) ws.close(code, reason);
  }

  const server = http.createServer((req, res) => { handleHttp(req, res); });
  server.on('upgrade', handleUpgrade);
  server.on('clientError', (err, socket) => { try { socket.destroy(); } catch (_) {} });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      try { ws.ping(); } catch (_) {}
    }
    linkLimiter.prune();
    linkFailures.prune();
    handshakeLimiter.prune();
    chatLimiter.prune();
  }, HEARTBEAT_MS);
  heartbeat.unref?.();

  return {
    listen() {
      return new Promise((resolve) => {
        server.once('error', (err) => { warn(`[MOD] Gateway could not start on ${host}:${port}: ${err.message}`); resolve(false); });
        server.listen(port, host, () => { log(`[MOD] Gateway listening on http://${host}:${port}/mod`); resolve(true); });
      });
    },
    /**
     * Sends one message per connection; `build(discordId|null)` returns the
     * fields for that connection, or null to skip it.
     */
    broadcast(type, build) {
      let sent = 0;
      for (const ws of wss.clients) {
        if (ws.readyState !== ws.OPEN || ws.discordId === undefined) continue;
        const fields = build(ws.discordId || null);
        if (fields) { send(ws, type, fields); sent++; }
      }
      return sent;
    },
    /** Sends to one linked account. Returns true when it was online. */
    sendToAccount(discordId, type, fields) {
      const ws = byAccount.get(discordId);
      if (!ws || ws.readyState !== ws.OPEN) return false;
      send(ws, type, fields);
      return true;
    },
    closeAccount,
    /** True while the account's game is connected. */
    isOnline(discordId) {
      const ws = byAccount.get(discordId);
      return Boolean(ws && ws.readyState === ws.OPEN);
    },
    /** Closes everything (admin switched the gateway off). */
    closeAll(code = 1001, reason = 'Going away') {
      for (const ws of wss.clients) ws.close(code, reason);
    },
    stats() {
      return { sockets: wss.clients.size, linkedOnline: byAccount.size, linkedAccounts: linkStore.linkedCount() };
    },
    close() {
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

module.exports = {
  PROTOCOL_VERSION,
  CODE_TTL_MS,
  NETWORK_HISTORY_MS,
  RELINK_MESSAGES,
  networkOf,
  linkServerId,
  normalizeUuid,
  MAX_CHAT_CHARS,
  LIMITS,
  hashToken,
  normalizeCode,
  clientIp,
  RateWindow,
  createLinkStore,
  createModGateway,
};

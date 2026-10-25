import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';

const MAX_BYTES = 65_536;
const RATE_WINDOW_MS = 60_000;
const RATE_LIMIT = 180;
const devices = new Map();
const sessions = new Map();
const now = () => Date.now();
function validId(value) { return typeof value === 'string' && /^[A-Za-z0-9._:-]{8,160}$/.test(value); }
function parse(raw) { if (raw.length > MAX_BYTES) throw Error('message too large'); const m = JSON.parse(raw); if (m?.version !== 1 || typeof m.type !== 'string' || !m.body || typeof m.body !== 'object') throw Error('bad envelope'); return m; }
function rateOk(ws) { const time = now(); ws.rate = (ws.rate || []).filter(x => x > time - RATE_WINDOW_MS); if (ws.rate.length >= RATE_LIMIT) return false; ws.rate.push(time); return true; }
function send(ws, type, body) { if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ version: 1, type, requestId: crypto.randomUUID(), timestampMs: now(), body })); }
function close(ws, code, reason) { try { ws.close(code, reason); } catch {} }
const server = http.createServer((req,res) => { if (req.url === '/health') { res.writeHead(200, {'content-type':'application/json'}); res.end(JSON.stringify({ok:true, devices:devices.size})); } else { res.writeHead(404); res.end(); } });
const wss = new WebSocketServer({ server, path: '/relay', maxPayload: MAX_BYTES });
wss.on('connection', ws => {
  ws.on('message', raw => { try { if (!rateOk(ws)) return close(ws, 1008, 'rate limit'); const m = parse(raw.toString()); const b = m.body;
    if (m.type === 'register-device') { if (!validId(b.deviceId) || !validId(b.deviceProof)) throw Error('bad device registration'); if (devices.has(b.deviceId)) close(devices.get(b.deviceId), 4001, 'replaced'); ws.role='device'; ws.deviceId=b.deviceId; ws.deviceProof=b.deviceProof; devices.set(b.deviceId,ws); return send(ws,'registered',{deviceId:b.deviceId}); }
    if (m.type === 'connect-device') { if (!validId(b.deviceId) || !validId(b.sessionId) || !validId(b.pairProof)) throw Error('bad session'); const device=devices.get(b.deviceId); if (!device) return send(ws,'session-status',{state:'offline'}); ws.role='desktop'; ws.sessionId=b.sessionId; sessions.set(b.sessionId,{desktop:ws,deviceId:b.deviceId}); send(device,'incoming-session',{sessionId:b.sessionId,pairProof:b.pairProof,computerName:String(b.computerName||'Windows').slice(0,60)}); return; }
    if (m.type === 'session-accepted' || m.type === 'session-rejected') { if (ws.role !== 'device' || !validId(b.sessionId)) throw Error('unauthorized'); const s=sessions.get(b.sessionId); if (!s || s.deviceId !== ws.deviceId) throw Error('unknown session'); if (m.type === 'session-rejected') { send(s.desktop,'session-status',{state:'rejected'}); sessions.delete(b.sessionId); } else { s.device=ws; send(s.desktop,'session-status',{state:'accepted'}); } return; }
    if (m.type === 'signal' || m.type === 'control' || m.type === 'heartbeat') { const s=sessions.get(m.sessionId); if (!s || (ws !== s.desktop && ws !== s.device)) throw Error('unauthorized session'); send(ws === s.desktop ? s.device : s.desktop,m.type,b); return; }
    throw Error('unknown message');
  } catch (e) { send(ws,'error',{code:'invalid-request',message:e.message}); } });
  ws.on('close', () => { if (ws.deviceId && devices.get(ws.deviceId) === ws) devices.delete(ws.deviceId); for (const [id,s] of sessions) if (s.desktop===ws || s.device===ws) { send(s.desktop===ws?s.device:s.desktop,'session-status',{state:'disconnected'}); sessions.delete(id); } });
});
const port = Number(process.env.PORT || 8787); server.listen(port, '0.0.0.0', () => console.log(`Android Mirror relay listening on ${port}`));

import { DurableObject } from "cloudflare:workers";
import { Env, WireMessage } from "./types";

const MAX_PAYLOAD_BYTES = 100 * 1024; // 100 KB
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_MAX_MESSAGES = 30;

export class ClipboardRoom extends DurableObject {
  private messageTimestamps: number[] = [];

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    // Initialize SQLite persistence for the latest encrypted clip
    this.ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS latest_clip (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        message TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      )
    `);
  }

  async fetch(request: Request): Promise<Response> {
    const webSocketPair = new WebSocketPair();
    const [client, server] = Object.values(webSocketPair);

    // Accept WebSocket into Hibernation API
    this.ctx.acceptWebSocket(server);

    // Deliver the latest encrypted clip if available
    try {
      const cursor = this.ctx.storage.sql.exec(`SELECT message FROM latest_clip WHERE id = 1`);
      for (const row of cursor) {
        if (row && typeof row.message === "string") {
          server.send(row.message);
        }
      }
    } catch (e) {
      console.error("Failed to query latest_clip from SQLite:", e);
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;

    if (message.length > MAX_PAYLOAD_BYTES) {
      ws.send(JSON.stringify({ error: "Payload exceeds 100 KB limit" }));
      return;
    }

    let parsed: WireMessage;
    try {
      parsed = JSON.parse(message);
    } catch {
      return; // Discard invalid JSON
    }

    if (parsed.type === "ping") {
      ws.send(JSON.stringify({ type: "pong" }));
      return;
    }

    if (parsed.type === "clip") {
      // Basic rate limiting
      const now = Date.now();
      this.messageTimestamps = this.messageTimestamps.filter(t => now - t < RATE_LIMIT_WINDOW_MS);
      if (this.messageTimestamps.length >= RATE_LIMIT_MAX_MESSAGES) {
        ws.send(JSON.stringify({ error: "Rate limit exceeded. Max 30 clips per minute." }));
        return;
      }
      this.messageTimestamps.push(now);

      // Validate envelope fields
      if (!parsed.nonce || !parsed.ciphertext || typeof parsed.timestamp !== "number") {
        return;
      }

      // Persist the newest encrypted clip into SQLite
      try {
        this.ctx.storage.sql.exec(`
          INSERT OR REPLACE INTO latest_clip (id, message, updated_at)
          VALUES (1, ?, ?)
        `, message, now);
      } catch (e) {
        console.error("Failed to store clip in SQLite:", e);
      }

      // Relay to all other connected clients in this room
      for (const client of this.ctx.getWebSockets()) {
        if (client !== ws) {
          try {
            client.send(message);
          } catch {
            // Client might be terminating
          }
        }
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean): Promise<void> {
    // Hibernation event callback - connection is already closing/closed.
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    // Hibernation error callback.
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/") {
      return new Response("cross-clip relay operational", {
        status: 200,
        headers: { "Content-Type": "text/plain" }
      });
    }

    if (url.pathname.startsWith("/ws/")) {
      const roomId = url.pathname.slice(4).trim();
      if (!roomId || roomId.length < 8 || roomId.length > 64) {
        return new Response("Invalid room ID (must be 8-64 chars)", { status: 400 });
      }

      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Expected Upgrade: websocket", { status: 426 });
      }

      const id = env.ROOMS.idFromName(roomId);
      const room = env.ROOMS.get(id);
      return room.fetch(request);
    }

    return new Response("Not Found", { status: 404 });
  }
};

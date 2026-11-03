import assert from "node:assert";

const PORT = process.env.TEST_PORT || 8787;
const BASE_WS = `ws://127.0.0.1:${PORT}/ws/test-room-12345678`;

console.log(`[Test] Connecting Client A and Client B to ${BASE_WS}...`);

const wsA = new WebSocket(BASE_WS);
const wsB = new WebSocket(BASE_WS);

await Promise.all([
  new Promise((res, rej) => { wsA.onopen = res; wsA.onerror = rej; }),
  new Promise((res, rej) => { wsB.onopen = res; wsB.onerror = rej; })
]);

console.log("[Test] Both clients connected successfully!");

// Test Ping-Pong
const pingPromise = new Promise((resolve) => {
  wsA.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.type === "pong") {
      console.log("[Test] Ping-Pong verified!");
      resolve();
    }
  };
});
wsA.send(JSON.stringify({ type: "ping" }));
await pingPromise;

// Test Relay from Client A to Client B
const clipPayload = {
  type: "clip",
  nonce: "dGVzdC1ub25jZQ==",
  ciphertext: "ZW5jcnlwdGVkLWNsaXAtZGF0YQ==",
  timestamp: Date.now()
};

const relayPromise = new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error("Relay message timeout")), 4000);
  wsB.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data.type === "clip") {
        clearTimeout(timeout);
        assert.strictEqual(data.nonce, clipPayload.nonce);
        assert.strictEqual(data.ciphertext, clipPayload.ciphertext);
        console.log("[Test] Client B received relayed clip successfully!");
        resolve();
      }
    } catch (e) {
      reject(e);
    }
  };
});

wsA.send(JSON.stringify(clipPayload));
await relayPromise;

wsA.close();
wsB.close();

// Test Reconnection / Persistence: Client C should receive the latest clip upon connecting
console.log("[Test] Connecting Client C to verify DO SQLite state persistence...");
const wsC = new WebSocket(BASE_WS);
const catchUpPromise = new Promise((resolve, reject) => {
  const timeout = setTimeout(() => reject(new Error("Catch-up clip timeout")), 4000);
  wsC.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      if (data.type === "clip") {
        clearTimeout(timeout);
        assert.strictEqual(data.ciphertext, clipPayload.ciphertext);
        console.log("[Test] Client C received persisted latest clip!");
        resolve();
      }
    } catch (e) {
      reject(e);
    }
  };
});

await catchUpPromise;
wsC.close();

console.log("=== ALL SERVER TESTS PASSED SUCCESSFULLY! ===");
process.exit(0);

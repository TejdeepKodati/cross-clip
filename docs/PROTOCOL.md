# cross-clip Protocol Specification v1

## 1. Overview
`cross-clip` provides zero-knowledge, end-to-end encrypted (E2EE) bidirectional clipboard synchronization over a cloud relay. The relay server (Cloudflare Worker + Durable Object) sees only an opaque Room ID and encrypted payloads. Plaintext clipboard data is encrypted and decrypted strictly on the edge devices (Windows PC and Android phone).

---

## 2. Cryptographic Scheme

### 2.1 Master Secret Generation
- Generated on the Windows client upon initial setup using a Cryptographically Secure Pseudorandom Number Generator (CSPRNG).
- Entropy: 32 bytes (256 bits).
- Format: Base32 encoded string formatted in readable 4-character chunks for manual entry or presented as a QR code:
  `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`

### 2.2 Key Derivation Function (HKDF-SHA256)
Using RFC 5869 HKDF with SHA-256 and constant salt `"cross-clip-v1"`:
1. **Extract**:
   `PRK = HMAC-SHA256(salt="cross-clip-v1", IKM=MasterSecret)`
2. **Expand Room ID**:
   `RoomKey = HKDF-Expand(PRK, info="cross-clip-room-id", length=16)`
   Encoded as a 32-character lowercase hexadecimal string: `roomId = Hex(RoomKey)`.
   *Only this derived Room ID is sent to the Cloudflare relay.*
3. **Expand AES Key**:
   `AESKey = HKDF-Expand(PRK, info="cross-clip-aes-key", length=32)`
   This 256-bit symmetric key remains strictly on the client devices.

### 2.3 Encryption & Decryption (AES-256-GCM)
- Algorithm: AES-256 in Galois/Counter Mode (GCM).
- Initialization Vector (IV / Nonce): 12 bytes randomly generated per message via CSPRNG. Never reused.
- Authentication Tag: 16 bytes (128 bits) appended to the ciphertext.
- Additional Authenticated Data (AAD): Monotonically increasing Unix millisecond timestamp (8-byte big-endian or string representation) to prevent replay/tampering.

---

## 3. Wire Protocol (WebSocket)

### 3.1 Connection
- URL: `wss://<worker-domain>/ws/<roomId>`
- Handshake: Standard HTTP `Upgrade: websocket`.
- The server associates the connection with a Durable Object instance keyed by `roomId`.

### 3.2 Message Envelope
All WebSocket payloads are UTF-8 JSON text.

#### Encrypted Clip Broadcast (Client -> Server -> Other Clients)
```json
{
  "type": "clip",
  "nonce": "<Base64 encoded 12-byte IV>",
  "ciphertext": "<Base64 encoded (ciphertext + 16-byte GCM tag)>",
  "timestamp": 1729000000000
}
```

#### Keepalive / Hibernation Ping-Pong
To preserve connections across cellular NAT and Cloudflare Worker hibernation:
- Client or server sends standard WebSocket Ping frames or JSON ping:
```json
{ "type": "ping" }
```
- Receiver responds with:
```json
{ "type": "pong" }
```

### 3.3 Plaintext Data Format (Encrypted Payload)
Inside `ciphertext`, the decrypted bytes decode to UTF-8 JSON:
```json
{
  "text": "User clipboard text string",
  "sender": "windows" | "android",
  "timestamp": 1729000000000,
  "hash": "<SHA-256 hex string of text>"
}
```

---

## 4. Relay Server Guarantees & Constraints
- **Zero-Knowledge**: Server cannot decrypt messages; it has no access to the Master Secret or the derived AES key.
- **State Storage**: The Durable Object retains only the single most recent encrypted clip in its SQLite storage. When a device joins or reconnects, the DO immediately delivers this latest clip.
- **Size Limit**: Maximum WebSocket message size is 100 KB (102,400 bytes). Oversized messages are dropped with an error close code (4009 / Message Too Large).
- **Rate Limiting**: Maximum 30 clip updates per minute per room. Spurious floods are discarded to conserve bandwidth and CPU.

---

## 5. Echo-Loop Prevention
When Device A sends a clip to Device B:
1. Device B decrypts the clip and calculates `H = SHA-256(text)`.
2. Device B saves `lastKnownHash = H`.
3. Device B updates its local OS clipboard.
4. When Device B's local clipboard change listener fires, it reads the new clipboard and computes its SHA-256 hash.
5. If the hash matches `lastKnownHash`, Device B silently ignores the change event.
6. If the hash differs (user copied something new locally), `lastKnownHash` is updated and the new clip is encrypted and broadcast.

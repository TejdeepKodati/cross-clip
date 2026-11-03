export interface Env {
  ROOMS: DurableObjectNamespace;
}

export interface EncryptedClipEnvelope {
  type: "clip";
  nonce: string;
  ciphertext: string;
  timestamp: number;
}

export interface PingEnvelope {
  type: "ping";
}

export interface PongEnvelope {
  type: "pong";
}

export type WireMessage = EncryptedClipEnvelope | PingEnvelope | PongEnvelope;

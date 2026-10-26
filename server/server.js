const { WebSocketServer, WebSocket } = require('ws');
const http = require('http');
const url = require('url');

const PORT = process.env.PORT || 3000;
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Clipboard Sync Relay is Active (Status: OK)\n');
});

const wss = new WebSocketServer({ server });
const rooms = new Map();

wss.on('connection', (ws, req) => {
    const parameters = url.parse(req.url, true).query;
    const token = parameters.token;
    const deviceId = parameters.deviceId || 'unknown';

    if (!token) {
        ws.close(4001, 'Pairing token is required');
        return;
    }

    if (!rooms.has(token)) {
        rooms.set(token, new Set());
    }

    const room = rooms.get(token);
    room.add(ws);
    ws.token = token;
    ws.deviceId = deviceId;
    ws.isAlive = true;

    console.log(`[+] Device connected: ${deviceId} to room ${token.substring(0, 4)}***`);

    ws.on('pong', () => {
        ws.isAlive = true;
    });

    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            if (data.type === 'CLIPBOARD_SYNC' && data.payload) {
                const targetRoom = rooms.get(token);
                if (targetRoom) {
                    const outgoing = JSON.stringify(data);
                    targetRoom.forEach((client) => {
                        if (client !== ws && client.readyState === WebSocket.OPEN) {
                            client.send(outgoing);
                        }
                    });
                }
            }
        } catch (err) {
            console.error('Error handling message:', err.message);
        }
    });

    ws.on('close', () => {
        const currentRoom = rooms.get(token);
        if (currentRoom) {
            currentRoom.delete(ws);
            if (currentRoom.size === 0) {
                rooms.delete(token);
            }
        }
        console.log(`[-] Device disconnected: ${deviceId}`);
    });
});

const interval = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (!ws.isAlive) return ws.terminate();
        ws.isAlive = false;
        ws.ping();
    });
}, 25000);

server.listen(PORT, () => {
    console.log(`Relay active on port ${PORT}`);
});

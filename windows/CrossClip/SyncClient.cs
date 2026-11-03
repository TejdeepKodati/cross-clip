using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace CrossClip
{
    public enum ConnectionState
    {
        Disconnected,
        Connecting,
        Connected
    }

    public class SyncClient : IDisposable
    {
        private readonly string _serverBaseUrl;
        private readonly byte[] _masterSecret;
        private readonly string _roomId;
        private readonly byte[] _aesKey;
        private readonly EchoLoopGuard _echoGuard;

        private ClientWebSocket? _ws;
        private CancellationTokenSource? _cts;
        private ConnectionState _state = ConnectionState.Disconnected;
        private readonly object _stateLock = new();

        public event Action<ConnectionState>? StateChanged;
        public event Action<string>? RemoteClipReceived;
        public event Action<string>? LogMessage;

        public ConnectionState State
        {
            get { lock (_stateLock) return _state; }
            private set
            {
                lock (_stateLock)
                {
                    if (_state == value) return;
                    _state = value;
                }
                StateChanged?.Invoke(value);
            }
        }

        public string RoomId => _roomId;

        public SyncClient(string serverUrl, string masterSecretBase32, EchoLoopGuard echoGuard)
        {
            _serverBaseUrl = serverUrl.TrimEnd('/');
            _masterSecret = CryptoService.DecodeBase32(masterSecretBase32);
            var (roomId, aesKey) = CryptoService.DeriveKeys(_masterSecret);
            _roomId = roomId;
            _aesKey = aesKey;
            _echoGuard = echoGuard;
        }

        public void Start()
        {
            _cts = new CancellationTokenSource();
            Task.Run(() => ConnectionLoopAsync(_cts.Token));
        }

        private async Task ConnectionLoopAsync(CancellationToken ct)
        {
            int attempt = 0;
            var random = new Random();

            while (!ct.IsCancellationRequested)
            {
                try
                {
                    State = ConnectionState.Connecting;
                    Log("Connecting to relay...");

                    string wsUrl = BuildWebSocketUrl(_serverBaseUrl, _roomId);
                    using var ws = new ClientWebSocket();
                    _ws = ws;

                    await ws.ConnectAsync(new Uri(wsUrl), ct);
                    State = ConnectionState.Connected;
                    attempt = 0;
                    Log("Connected to relay!");

                    using var pingCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    var pingTask = RunPingLoopAsync(ws, pingCts.Token);
                    var receiveTask = RunReceiveLoopAsync(ws, ct);

                    await Task.WhenAny(pingTask, receiveTask);
                    pingCts.Cancel();
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception ex)
                {
                    Log($"Connection error: {ex.Message}");
                }
                finally
                {
                    State = ConnectionState.Disconnected;
                    _ws = null;
                }

                if (ct.IsCancellationRequested) break;

                attempt++;
                int delaySec = Math.Min(30, (int)Math.Pow(2, Math.Min(attempt, 5)));
                int jitterMs = random.Next(100, 1000);
                int totalDelay = (delaySec * 1000) + jitterMs;
                Log($"Reconnecting in {delaySec}s...");

                try
                {
                    await Task.Delay(totalDelay, ct);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }

        private async Task RunReceiveLoopAsync(ClientWebSocket ws, CancellationToken ct)
        {
            var buffer = new byte[16 * 1024];

            while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                using var ms = new MemoryStream();
                WebSocketReceiveResult result;

                do
                {
                    result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", ct);
                        return;
                    }
                    ms.Write(buffer, 0, result.Count);
                }
                while (!result.EndOfMessage);

                string message = Encoding.UTF8.GetString(ms.ToArray());
                ProcessIncomingMessage(message);
            }
        }

        private void ProcessIncomingMessage(string json)
        {
            try
            {
                using var doc = JsonDocument.Parse(json);
                var root = doc.RootElement;
                if (!root.TryGetProperty("type", out var typeProp)) return;

                string type = typeProp.GetString() ?? "";
                if (type == "pong") return;

                if (type == "clip")
                {
                    var envelope = JsonSerializer.Deserialize<EncryptedEnvelope>(json);
                    if (envelope == null) return;

                    var clip = CryptoService.DecryptClip(envelope, _aesKey);
                    if (clip == null) return;

                    // Suppress our own echoes if sender is windows
                    if (clip.Sender == "windows") return;

                    if (_echoGuard.ShouldApplyRemoteClip(clip.Hash))
                    {
                        Log($"Received clip from {clip.Sender} ({clip.Text.Length} chars)");
                        RemoteClipReceived?.Invoke(clip.Text);
                    }
                }
            }
            catch (Exception ex)
            {
                Log($"Failed to process incoming message: {ex.Message}");
            }
        }

        private async Task RunPingLoopAsync(ClientWebSocket ws, CancellationToken ct)
        {
            byte[] pingBytes = Encoding.UTF8.GetBytes("{\"type\":\"ping\"}");

            while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
            {
                try
                {
                    await Task.Delay(30000, ct);
                    if (ws.State == WebSocketState.Open)
                    {
                        await ws.SendAsync(new ArraySegment<byte>(pingBytes), WebSocketMessageType.Text, true, ct);
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch
                {
                    break;
                }
            }
        }

        public async Task SendLocalClipAsync(string text)
        {
            var ws = _ws;
            if (ws == null || ws.State != WebSocketState.Open) return;

            try
            {
                var envelope = CryptoService.EncryptClip(text, "windows", _aesKey);
                string json = JsonSerializer.Serialize(envelope);
                byte[] bytes = Encoding.UTF8.GetBytes(json);

                if (bytes.Length > 100 * 1024)
                {
                    Log("Clip exceeds 100 KB limit. Skipped.");
                    return;
                }

                await ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
                Log($"Sent clip to phone ({text.Length} chars)");
            }
            catch (Exception ex)
            {
                Log($"Error sending clip: {ex.Message}");
            }
        }

        private static string BuildWebSocketUrl(string baseUrl, string roomId)
        {
            string clean = baseUrl.TrimEnd('/');
            if (clean.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                clean = "wss://" + clean.Substring(8);
            }
            else if (clean.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
            {
                clean = "ws://" + clean.Substring(7);
            }
            else if (!clean.StartsWith("wss://", StringComparison.OrdinalIgnoreCase) && !clean.StartsWith("ws://", StringComparison.OrdinalIgnoreCase))
            {
                clean = "wss://" + clean;
            }

            return $"{clean}/ws/{roomId}";
        }

        private void Log(string msg)
        {
            LogMessage?.Invoke($"[{DateTime.Now:HH:mm:ss}] {msg}");
        }

        public void Dispose()
        {
            _cts?.Cancel();
            try { _ws?.Dispose(); } catch { }
        }
    }
}

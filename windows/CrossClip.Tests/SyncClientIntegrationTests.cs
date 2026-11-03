using System;
using System.Net.WebSockets;
using System.Threading;
using System.Threading.Tasks;
using Xunit;

namespace CrossClip.Tests
{
    public class SyncClientIntegrationTests
    {
        [Fact]
        public async Task TestConnectDirectClientWebSocket()
        {
            using var ws = new ClientWebSocket();
            var uri = new Uri("wss://cross-clip-relay.tejdeepkodati996.workers.dev/ws/cba59b21034bf23e4b781877f52b3531");
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            await ws.ConnectAsync(uri, cts.Token);
            Assert.Equal(WebSocketState.Open, ws.State);
            await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing test", CancellationToken.None);
        }

        [Fact]
        public void TestClipboardListenerRegistration()
        {
            using var listener = new ClipboardListener();
            Assert.NotNull(listener);
        }
    }
}

using CrossClip;
using Xunit;

namespace CrossClip.Tests
{
    public class EchoLoopGuardTests
    {
        [Fact]
        public void LocalCopy_FirstTime_ReturnsTrue()
        {
            var guard = new EchoLoopGuard();
            bool result = guard.ShouldProcessLocalCopy("Copied Text", out string hash);

            Assert.True(result);
            Assert.NotEmpty(hash);
            Assert.Equal(hash, guard.LastKnownHash);
        }

        [Fact]
        public void LocalCopy_IdenticalConsecutiveCopy_ReturnsFalse()
        {
            var guard = new EchoLoopGuard();
            guard.ShouldProcessLocalCopy("Copied Text", out _);

            bool duplicate = guard.ShouldProcessLocalCopy("Copied Text", out string hash2);

            Assert.False(duplicate);
        }

        [Fact]
        public void RemoteClip_SameHash_IsRejected()
        {
            var guard = new EchoLoopGuard();
            guard.ShouldProcessLocalCopy("Local text", out string hash);

            // Remote clip with identical hash should not be reapplied
            bool applyRemote = guard.ShouldApplyRemoteClip(hash);
            Assert.False(applyRemote);
        }

        [Fact]
        public void RemoteClip_NewHash_IsAccepted_AndBlocksImmediateLocalEcho()
        {
            var guard = new EchoLoopGuard();
            string newRemoteText = "New text from phone";
            string remoteHash = CryptoService.ComputeSha256(newRemoteText);

            bool applyRemote = guard.ShouldApplyRemoteClip(remoteHash);
            Assert.True(applyRemote);

            // Simulating OS clipboard listener firing after we pasted remote clip
            bool localEcho = guard.ShouldProcessLocalCopy(newRemoteText, out _);
            Assert.False(localEcho); // Suppressed!
        }
    }
}

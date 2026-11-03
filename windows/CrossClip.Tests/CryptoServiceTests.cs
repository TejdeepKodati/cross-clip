using System.Text;
using CrossClip;
using Xunit;

namespace CrossClip.Tests
{
    public class CryptoServiceTests
    {
        [Fact]
        public void DeriveKeys_ProducesConsistentRoomIdAndAesKey()
        {
            byte[] secret = CryptoService.GenerateMasterSecret();
            Assert.Equal(32, secret.Length);

            var (roomId1, aesKey1) = CryptoService.DeriveKeys(secret);
            var (roomId2, aesKey2) = CryptoService.DeriveKeys(secret);

            Assert.Equal(roomId1, roomId2);
            Assert.Equal(aesKey1, aesKey2);
            Assert.Equal(32, roomId1.Length); // 16 bytes = 32 hex chars
            Assert.Equal(32, aesKey1.Length); // 256 bits = 32 bytes
        }

        [Fact]
        public void Base32_RoundtripPreservesBytes()
        {
            byte[] original = CryptoService.GenerateMasterSecret();
            string encoded = CryptoService.EncodeBase32(original);
            byte[] decoded = CryptoService.DecodeBase32(encoded);

            Assert.Equal(original, decoded);
        }

        [Fact]
        public void Base32_FormattedPairingCodeDecodesCorrectly()
        {
            byte[] original = CryptoService.GenerateMasterSecret();
            string encoded = CryptoService.EncodeBase32(original);
            string formatted = CryptoService.FormatPairingCode(encoded);

            Assert.Contains("-", formatted);
            byte[] decoded = CryptoService.DecodeBase32(formatted);

            Assert.Equal(original, decoded);
        }

        [Fact]
        public void EncryptDecrypt_RoundtripMatchesOriginalText()
        {
            byte[] secret = CryptoService.GenerateMasterSecret();
            var (_, aesKey) = CryptoService.DeriveKeys(secret);

            string sampleText = "Hello from Windows! This is a test clipboard text with special characters: 🚀 123 !@#$%^&*()_+";
            var envelope = CryptoService.EncryptClip(sampleText, "windows", aesKey);

            Assert.NotNull(envelope);
            Assert.Equal("clip", envelope.Type);
            Assert.NotEmpty(envelope.Nonce);
            Assert.NotEmpty(envelope.Ciphertext);

            var decrypted = CryptoService.DecryptClip(envelope, aesKey);

            Assert.NotNull(decrypted);
            Assert.Equal(sampleText, decrypted.Text);
            Assert.Equal("windows", decrypted.Sender);
            Assert.Equal(CryptoService.ComputeSha256(sampleText), decrypted.Hash);
        }

        [Fact]
        public void CheckSampleRoomId()
        {
            byte[] secret = CryptoService.DecodeBase32("33REWYNRZIPJTZOSLFFU44XUOKH5YLDXM5GZYJL5EOVUP6LHSQZA");
            var (roomId, _) = CryptoService.DeriveKeys(secret);
            Assert.Equal("cba59b21034bf23e4b781877f52b3531", roomId);
        }

        [Fact]
        public void DecryptClip_WithWrongKey_ThrowsOrFails()
        {
            byte[] secret1 = CryptoService.GenerateMasterSecret();
            byte[] secret2 = CryptoService.GenerateMasterSecret();

            var (_, key1) = CryptoService.DeriveKeys(secret1);
            var (_, key2) = CryptoService.DeriveKeys(secret2);

            var envelope = CryptoService.EncryptClip("Secret text", "windows", key1);

            Assert.ThrowsAny<System.Security.Cryptography.CryptographicException>(() =>
            {
                CryptoService.DecryptClip(envelope, key2);
            });
        }
    }
}

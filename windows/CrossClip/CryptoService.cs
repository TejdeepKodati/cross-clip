using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace CrossClip
{
    public class PlaintextClip
    {
        [JsonPropertyName("text")]
        public string Text { get; set; } = string.Empty;

        [JsonPropertyName("sender")]
        public string Sender { get; set; } = string.Empty;

        [JsonPropertyName("timestamp")]
        public long Timestamp { get; set; }

        [JsonPropertyName("hash")]
        public string Hash { get; set; } = string.Empty;
    }

    public class EncryptedEnvelope
    {
        [JsonPropertyName("type")]
        public string Type { get; set; } = "clip";

        [JsonPropertyName("nonce")]
        public string Nonce { get; set; } = string.Empty;

        [JsonPropertyName("ciphertext")]
        public string Ciphertext { get; set; } = string.Empty;

        [JsonPropertyName("timestamp")]
        public long Timestamp { get; set; }
    }

    public static class CryptoService
    {
        private static readonly byte[] Salt = Encoding.UTF8.GetBytes("cross-clip-v1");
        private static readonly byte[] InfoRoomId = Encoding.UTF8.GetBytes("cross-clip-room-id");
        private static readonly byte[] InfoAesKey = Encoding.UTF8.GetBytes("cross-clip-aes-key");

        public static byte[] GenerateMasterSecret()
        {
            var secret = new byte[32];
            RandomNumberGenerator.Fill(secret);
            return secret;
        }

        public static (string RoomId, byte[] AesKey) DeriveKeys(byte[] masterSecret)
        {
            if (masterSecret == null || masterSecret.Length != 32)
                throw new ArgumentException("Master secret must be 32 bytes.", nameof(masterSecret));

            byte[] prk = HKDF.Extract(HashAlgorithmName.SHA256, masterSecret, Salt);
            byte[] roomBytes = HKDF.Expand(HashAlgorithmName.SHA256, prk, 16, InfoRoomId);
            byte[] aesKey = HKDF.Expand(HashAlgorithmName.SHA256, prk, 32, InfoAesKey);

            string roomId = Convert.ToHexString(roomBytes).ToLowerInvariant();
            return (roomId, aesKey);
        }

        public static EncryptedEnvelope EncryptClip(string text, string sender, byte[] aesKey)
        {
            long timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string hash = ComputeSha256(text);

            var clip = new PlaintextClip
            {
                Text = text,
                Sender = sender,
                Timestamp = timestamp,
                Hash = hash
            };

            string json = JsonSerializer.Serialize(clip);
            byte[] plaintext = Encoding.UTF8.GetBytes(json);

            byte[] nonce = new byte[12];
            RandomNumberGenerator.Fill(nonce);

            byte[] ciphertext = new byte[plaintext.Length];
            byte[] tag = new byte[16];
            byte[] aad = new byte[8];
            System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(aad, timestamp);

            using (var aesGcm = new AesGcm(aesKey, 16))
            {
                aesGcm.Encrypt(nonce, plaintext, ciphertext, tag, aad);
            }

            // Concatenate ciphertext + tag into one buffer
            byte[] combined = new byte[ciphertext.Length + tag.Length];
            Buffer.BlockCopy(ciphertext, 0, combined, 0, ciphertext.Length);
            Buffer.BlockCopy(tag, 0, combined, ciphertext.Length, tag.Length);

            return new EncryptedEnvelope
            {
                Type = "clip",
                Nonce = Convert.ToBase64String(nonce),
                Ciphertext = Convert.ToBase64String(combined),
                Timestamp = timestamp
            };
        }

        public static PlaintextClip? DecryptClip(EncryptedEnvelope envelope, byte[] aesKey)
        {
            try
            {
                if (envelope == null || string.IsNullOrEmpty(envelope.Nonce) || string.IsNullOrEmpty(envelope.Ciphertext))
                    return null;

                byte[] nonce = Convert.FromBase64String(envelope.Nonce);
                byte[] combined = Convert.FromBase64String(envelope.Ciphertext);

                if (combined.Length < 16)
                    return null;

                int ciphertextLength = combined.Length - 16;
                byte[] ciphertext = new byte[ciphertextLength];
                byte[] tag = new byte[16];
                Buffer.BlockCopy(combined, 0, ciphertext, 0, ciphertextLength);
                Buffer.BlockCopy(combined, ciphertextLength, tag, 0, 16);

                byte[] plaintext = new byte[ciphertextLength];
                byte[] aad = new byte[8];
                System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(aad, envelope.Timestamp);

                using (var aesGcm = new AesGcm(aesKey, 16))
                {
                    aesGcm.Decrypt(nonce, ciphertext, tag, plaintext, aad);
                }

                string json = Encoding.UTF8.GetString(plaintext);
                return JsonSerializer.Deserialize<PlaintextClip>(json, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
            }
            catch (Exception ex)
            {
                Logger.Log($"[CryptoService] Decrypt error: {ex.Message}");
                return null;
            }
        }

        public static string ComputeSha256(string input)
        {
            byte[] bytes = SHA256.HashData(Encoding.UTF8.GetBytes(input));
            return Convert.ToHexString(bytes).ToLowerInvariant();
        }

        #region Base32 Encoding/Decoding
        private const string Base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        public static string EncodeBase32(byte[] data)
        {
            if (data == null || data.Length == 0) return string.Empty;
            var sb = new StringBuilder();
            int buffer = data[0];
            int next = 1;
            int bitsLeft = 8;
            while (bitsLeft > 0 || next < data.Length)
            {
                if (bitsLeft < 5)
                {
                    if (next < data.Length)
                    {
                        buffer <<= 8;
                        buffer |= (data[next++] & 0xFF);
                        bitsLeft += 8;
                    }
                    else
                    {
                        int pad = 5 - bitsLeft;
                        buffer <<= pad;
                        bitsLeft = 5;
                    }
                }
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                sb.Append(Base32Chars[index]);
            }
            return sb.ToString();
        }

        public static byte[] DecodeBase32(string input)
        {
            if (string.IsNullOrWhiteSpace(input)) return Array.Empty<byte>();
            string clean = input.Replace("-", "").Replace(" ", "").Trim().ToUpperInvariant();
            var output = new MemoryStream();
            int buffer = 0;
            int bitsLeft = 0;

            foreach (char c in clean)
            {
                int val = Base32Chars.IndexOf(c);
                if (val < 0) continue;
                buffer = (buffer << 5) | val;
                bitsLeft += 5;
                if (bitsLeft >= 8)
                {
                    bitsLeft -= 8;
                    output.WriteByte((byte)((buffer >> bitsLeft) & 0xFF));
                }
            }
            return output.ToArray();
        }

        public static string FormatPairingCode(string base32)
        {
            var sb = new StringBuilder();
            for (int i = 0; i < base32.Length; i++)
            {
                if (i > 0 && i % 4 == 0) sb.Append('-');
                sb.Append(base32[i]);
            }
            return sb.ToString();
        }
        #endregion
    }
}

namespace CrossClip
{
    public class EchoLoopGuard
    {
        private readonly object _lock = new();
        private string _lastKnownHash = string.Empty;

        public string LastKnownHash
        {
            get
            {
                lock (_lock) return _lastKnownHash;
            }
        }

        public bool ShouldProcessLocalCopy(string text, out string hash)
        {
            hash = string.Empty;
            if (string.IsNullOrEmpty(text))
                return false;

            hash = CryptoService.ComputeSha256(text);
            lock (_lock)
            {
                if (hash == _lastKnownHash)
                {
                    return false;
                }
                _lastKnownHash = hash;
                return true;
            }
        }

        public bool ShouldApplyRemoteClip(string hash)
        {
            if (string.IsNullOrEmpty(hash))
                return false;

            lock (_lock)
            {
                if (hash == _lastKnownHash)
                {
                    return false;
                }
                _lastKnownHash = hash;
                return true;
            }
        }

        public void Reset()
        {
            lock (_lock)
            {
                _lastKnownHash = string.Empty;
            }
        }
    }
}

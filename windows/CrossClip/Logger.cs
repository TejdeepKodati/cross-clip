using System;
using System.IO;

namespace CrossClip
{
    public static class Logger
    {
        private static readonly object _lock = new();
        private static readonly string LogFile = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "cross-clip",
            "debug.log"
        );

        public static void Log(string message)
        {
            try
            {
                lock (_lock)
                {
                    File.AppendAllText(LogFile, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {message}\r\n");
                }
            }
            catch { }
        }
    }
}

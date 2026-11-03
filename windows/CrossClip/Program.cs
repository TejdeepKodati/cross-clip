using System;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace CrossClip
{
    internal static class Program
    {
        private static Mutex? _mutex;
        private static readonly string LogFile = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "cross-clip",
            "debug.log"
        );

        [STAThread]
        static void Main()
        {
            try
            {
                File.AppendAllText(LogFile, $"[{DateTime.Now}] CrossClip Main starting...\n");

                string mutexName = $"Local\\CrossClipSingleInstance_{Environment.UserName}";
                _mutex = new Mutex(true, mutexName, out bool createdNew);

                if (!createdNew)
                {
                    File.AppendAllText(LogFile, $"[{DateTime.Now}] Mutex already owned. Exiting.\n");
                    return;
                }

                Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
                AppDomain.CurrentDomain.UnhandledException += (s, e) =>
                {
                    File.AppendAllText(LogFile, $"[{DateTime.Now}] Unhandled domain exception: {e.ExceptionObject}\n");
                };
                Application.ThreadException += (s, e) =>
                {
                    File.AppendAllText(LogFile, $"[{DateTime.Now}] Thread exception: {e.Exception}\n");
                };

                ApplicationConfiguration.Initialize();
                File.AppendAllText(LogFile, $"[{DateTime.Now}] Initializing TrayApplicationContext...\n");
                var context = new TrayApplicationContext();
                File.AppendAllText(LogFile, $"[{DateTime.Now}] Running Application.Run...\n");
                Application.Run(context);
                File.AppendAllText(LogFile, $"[{DateTime.Now}] Application.Run exited cleanly.\n");

                _mutex.ReleaseMutex();
            }
            catch (Exception ex)
            {
                File.AppendAllText(LogFile, $"[{DateTime.Now}] Fatal exception: {ex}\n");
            }
        }
    }
}

using System;
using System.Threading;
using System.Windows.Forms;

namespace CrossClip
{
    internal static class Program
    {
        private static Mutex? _mutex;

        [STAThread]
        static void Main()
        {
            string mutexName = $"Global\\CrossClipSingleInstance_{Environment.UserName}";
            _mutex = new Mutex(true, mutexName, out bool createdNew);

            if (!createdNew)
            {
                MessageBox.Show(
                    "cross-clip is already running in the system tray.\nLook for the green icon near your Windows clock.",
                    "cross-clip",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information
                );
                return;
            }

            ApplicationConfiguration.Initialize();
            Application.Run(new TrayApplicationContext());

            _mutex.ReleaseMutex();
        }
    }
}

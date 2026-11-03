using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace CrossClip
{
    public class ClipboardListener : IDisposable
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        private static readonly IntPtr HWND_MESSAGE = (IntPtr)(-3);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool AddClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

        private readonly MessageOnlyWindow _window;
        public event Action<string>? ClipboardTextChanged;

        public ClipboardListener()
        {
            _window = new MessageOnlyWindow(this);
            AddClipboardFormatListener(_window.Handle);
        }

        private void OnClipboardUpdate()
        {
            string? text = ReadClipboardWithRetry();
            if (!string.IsNullOrEmpty(text))
            {
                ClipboardTextChanged?.Invoke(text);
            }
        }

        public static string? ReadClipboardWithRetry(int maxRetries = 5, int delayMs = 50)
        {
            for (int i = 0; i < maxRetries; i++)
            {
                try
                {
                    if (Clipboard.ContainsText())
                    {
                        return Clipboard.GetText();
                    }
                    return null;
                }
                catch (ExternalException)
                {
                    Thread.Sleep(delayMs);
                }
                catch (Exception)
                {
                    break;
                }
            }
            return null;
        }

        public static bool SetClipboardWithRetry(string text, int maxRetries = 5, int delayMs = 50)
        {
            for (int i = 0; i < maxRetries; i++)
            {
                try
                {
                    Clipboard.SetDataObject(text, true, 5, delayMs);
                    return true;
                }
                catch (ExternalException)
                {
                    Thread.Sleep(delayMs);
                }
                catch (Exception)
                {
                    break;
                }
            }
            return false;
        }

        public void Dispose()
        {
            RemoveClipboardFormatListener(_window.Handle);
            _window.DestroyHandle();
        }

        private class MessageOnlyWindow : NativeWindow
        {
            private readonly ClipboardListener _parent;

            public MessageOnlyWindow(ClipboardListener parent)
            {
                _parent = parent;
                var cp = new CreateParams
                {
                    Parent = HWND_MESSAGE
                };
                CreateHandle(cp);
            }

            protected override void WndProc(ref Message m)
            {
                if (m.Msg == WM_CLIPBOARDUPDATE)
                {
                    _parent.OnClipboardUpdate();
                }
                base.WndProc(ref m);
            }
        }
    }
}

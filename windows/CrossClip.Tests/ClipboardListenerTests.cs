using System;
using System.Threading;
using System.Windows.Forms;
using Xunit;

namespace CrossClip.Tests
{
    public class ClipboardListenerTests
    {
        [Fact]
        public void TestClipboardUpdateFires()
        {
            // Run on STA thread
            var tcs = new TaskCompletionSource<bool>();
            var thread = new Thread(() =>
            {
                try
                {
                    using var listener = new ClipboardListener();
                    string expected = "test-" + Guid.NewGuid();
                    string? received = null;

                    listener.ClipboardTextChanged += (text) =>
                    {
                        received = text;
                        Application.ExitThread();
                    };

                    // Set clipboard
                    Clipboard.SetDataObject(expected, true);

                    // Message pump for up to 2 seconds
                    var timer = new System.Windows.Forms.Timer { Interval = 2000 };
                    timer.Tick += (s, e) => { timer.Stop(); Application.ExitThread(); };
                    timer.Start();

                    Application.Run();

                    tcs.SetResult(received == expected);
                }
                catch (Exception ex)
                {
                    tcs.SetException(ex);
                }
            });

            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();

            Assert.True(tcs.Task.Result);
        }
    }
}

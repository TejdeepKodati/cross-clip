using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace CrossClip
{
    public class TrayApplicationContext : ApplicationContext
    {
        private readonly NotifyIcon _notifyIcon;
        private readonly ContextMenuStrip _contextMenu;
        private readonly ToolStripMenuItem _statusMenuItem;
        private readonly ToolStripMenuItem _startWithWindowsMenuItem;
        private readonly ToolStripMenuItem _showNotificationsMenuItem;

        private AppSettings _settings;
        private readonly EchoLoopGuard _echoGuard;
        private ClipboardListener _clipboardListener;
        private SyncClient _syncClient;
        private PairingForm? _activePairingForm;

        public TrayApplicationContext()
        {
            _settings = ConfigManager.LoadOrCreate();
            _echoGuard = new EchoLoopGuard();

            _contextMenu = new ContextMenuStrip();

            _statusMenuItem = new ToolStripMenuItem("Status: Starting...")
            {
                Enabled = false,
                Font = new Font("Segoe UI", 9, FontStyle.Bold)
            };
            _contextMenu.Items.Add(_statusMenuItem);
            _contextMenu.Items.Add(new ToolStripSeparator());

            var pairMenuItem = new ToolStripMenuItem("Show Pairing Code & QR", null, (s, e) => ShowPairingForm());
            _contextMenu.Items.Add(pairMenuItem);

            _startWithWindowsMenuItem = new ToolStripMenuItem("Start with Windows", null, (s, e) => ToggleStartWithWindows())
            {
                Checked = _settings.StartWithWindows
            };
            _contextMenu.Items.Add(_startWithWindowsMenuItem);

            _showNotificationsMenuItem = new ToolStripMenuItem("Show Notifications", null, (s, e) => ToggleShowNotifications())
            {
                Checked = _settings.ShowNotifications
            };
            _contextMenu.Items.Add(_showNotificationsMenuItem);

            _contextMenu.Items.Add(new ToolStripSeparator());

            var quitMenuItem = new ToolStripMenuItem("Quit", null, (s, e) => ExitApp());
            _contextMenu.Items.Add(quitMenuItem);

            _notifyIcon = new NotifyIcon
            {
                Icon = CreateAppIcon(),
                ContextMenuStrip = _contextMenu,
                Text = "cross-clip: Starting...",
                Visible = true
            };
            _notifyIcon.DoubleClick += (s, e) => ShowPairingForm();

            // Set up clipboard listener
            _clipboardListener = new ClipboardListener();
            _clipboardListener.ClipboardTextChanged += OnLocalClipboardChanged;

            // Set up sync client
            _syncClient = new SyncClient(_settings.ServerUrl, _settings.MasterSecret, _echoGuard);
            _syncClient.StateChanged += OnConnectionStateChanged;
            _syncClient.RemoteClipReceived += OnRemoteClipReceived;

            _syncClient.Start();
        }

        private void OnLocalClipboardChanged(string text)
        {
            if (_echoGuard.ShouldProcessLocalCopy(text, out _))
            {
                _ = _syncClient.SendLocalClipAsync(text);
            }
        }

        private void OnRemoteClipReceived(string text)
        {
            ClipboardListener.SetClipboardWithRetry(text);

            if (_settings.ShowNotifications)
            {
                string preview = text.Length > 40 ? text.Substring(0, 40) + "..." : text;
                _notifyIcon.ShowBalloonTip(1500, "cross-clip", $"Synced from phone: {preview}", ToolTipIcon.Info);
            }
        }

        private void OnConnectionStateChanged(ConnectionState state)
        {
            string text = state switch
            {
                ConnectionState.Connected => "Connected",
                ConnectionState.Connecting => "Connecting...",
                _ => "Disconnected"
            };

            _statusMenuItem.Text = $"Status: {text}";
            _notifyIcon.Text = $"cross-clip: {text}";
            _activePairingForm?.UpdateStatus(text);
        }

        private void ShowPairingForm()
        {
            if (_activePairingForm != null && !_activePairingForm.IsDisposed)
            {
                _activePairingForm.BringToFront();
                return;
            }

            string currentStatus = _syncClient.State.ToString();
            _activePairingForm = new PairingForm(_settings, currentStatus, (newSettings) =>
            {
                _settings = newSettings;
                ConfigManager.Save(_settings);

                // Restart sync client if server changed
                _syncClient.Dispose();
                _syncClient = new SyncClient(_settings.ServerUrl, _settings.MasterSecret, _echoGuard);
                _syncClient.StateChanged += OnConnectionStateChanged;
                _syncClient.RemoteClipReceived += OnRemoteClipReceived;
                _syncClient.Start();
            });

            _activePairingForm.Show();
        }

        private void ToggleStartWithWindows()
        {
            _settings.StartWithWindows = !_settings.StartWithWindows;
            _startWithWindowsMenuItem.Checked = _settings.StartWithWindows;
            ConfigManager.Save(_settings);
        }

        private void ToggleShowNotifications()
        {
            _settings.ShowNotifications = !_settings.ShowNotifications;
            _showNotificationsMenuItem.Checked = _settings.ShowNotifications;
            ConfigManager.Save(_settings);
        }

        private void ExitApp()
        {
            _notifyIcon.Visible = false;
            _clipboardListener.Dispose();
            _syncClient.Dispose();
            Application.Exit();
        }

        private static Icon CreateAppIcon()
        {
            using var bmp = new Bitmap(32, 32);
            using var g = Graphics.FromImage(bmp);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            // Draw a stylish clipboard sync icon
            using var brushBg = new SolidBrush(Color.FromArgb(16, 124, 65)); // Modern Green
            g.FillEllipse(brushBg, 1, 1, 30, 30);

            using var penWhite = new Pen(Color.White, 2.5f);
            // Draw overlapping link / clipboard loops
            g.DrawArc(penWhite, 7, 10, 11, 11, 135, 200);
            g.DrawArc(penWhite, 14, 10, 11, 11, -45, 200);

            IntPtr hIcon = bmp.GetHicon();
            return Icon.FromHandle(hIcon);
        }
    }
}

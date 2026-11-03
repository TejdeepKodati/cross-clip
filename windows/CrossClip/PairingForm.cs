using System;
using System.Drawing;
using System.Text.Json;
using System.Windows.Forms;
using QRCoder;

namespace CrossClip
{
    public class PairingForm : Form
    {
        private readonly AppSettings _settings;
        private readonly Action<AppSettings> _onSave;
        private readonly PictureBox _qrPictureBox;
        private readonly TextBox _serverUrlBox;
        private readonly TextBox _pairingCodeBox;
        private readonly Label _statusLabel;

        public PairingForm(AppSettings settings, string connectionStatus, Action<AppSettings> onSave)
        {
            _settings = settings;
            _onSave = onSave;

            Text = "cross-clip Pairing & Setup";
            Width = 460;
            Height = 560;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;

            var titleLabel = new Label
            {
                Text = "cross-clip Setup",
                Font = new Font("Segoe UI", 14, FontStyle.Bold),
                Location = new Point(20, 15),
                AutoSize = true
            };
            Controls.Add(titleLabel);

            _statusLabel = new Label
            {
                Text = $"Status: {connectionStatus}",
                Font = new Font("Segoe UI", 9, FontStyle.Regular),
                ForeColor = Color.DarkSlateGray,
                Location = new Point(22, 45),
                AutoSize = true
            };
            Controls.Add(_statusLabel);

            var serverLabel = new Label
            {
                Text = "Relay Server URL:",
                Location = new Point(20, 75),
                AutoSize = true,
                Font = new Font("Segoe UI", 9, FontStyle.Regular)
            };
            Controls.Add(serverLabel);

            _serverUrlBox = new TextBox
            {
                Text = _settings.ServerUrl,
                Location = new Point(20, 95),
                Width = 400,
                Font = new Font("Segoe UI", 9)
            };
            _serverUrlBox.TextChanged += (s, e) => UpdateQrCode();
            Controls.Add(_serverUrlBox);

            var codeLabel = new Label
            {
                Text = "Pairing Code (enter this on your Android phone):",
                Location = new Point(20, 130),
                AutoSize = true,
                Font = new Font("Segoe UI", 9, FontStyle.Regular)
            };
            Controls.Add(codeLabel);

            string formattedSecret = CryptoService.FormatPairingCode(_settings.MasterSecret);
            _pairingCodeBox = new TextBox
            {
                Text = formattedSecret,
                Location = new Point(20, 150),
                Width = 300,
                ReadOnly = true,
                Font = new Font("Consolas", 10, FontStyle.Bold)
            };
            Controls.Add(_pairingCodeBox);

            var copyBtn = new Button
            {
                Text = "Copy Code",
                Location = new Point(330, 149),
                Width = 90,
                Height = 26
            };
            copyBtn.Click += (s, e) =>
            {
                Clipboard.SetText(_pairingCodeBox.Text);
                MessageBox.Show("Pairing code copied to clipboard!", "Copied", MessageBoxButtons.OK, MessageBoxIcon.Information);
            };
            Controls.Add(copyBtn);

            var qrLabel = new Label
            {
                Text = "Scan QR Code on Phone App:",
                Location = new Point(20, 185),
                AutoSize = true,
                Font = new Font("Segoe UI", 9, FontStyle.Regular)
            };
            Controls.Add(qrLabel);

            _qrPictureBox = new PictureBox
            {
                Location = new Point(115, 210),
                Width = 200,
                Height = 200,
                BorderStyle = BorderStyle.FixedSingle,
                SizeMode = PictureBoxSizeMode.StretchImage
            };
            Controls.Add(_qrPictureBox);

            var saveBtn = new Button
            {
                Text = "Save & Apply",
                Location = new Point(160, 440),
                Width = 110,
                Height = 32,
                Font = new Font("Segoe UI", 9, FontStyle.Bold),
                DialogResult = DialogResult.OK
            };
            saveBtn.Click += (s, e) =>
            {
                _settings.ServerUrl = _serverUrlBox.Text.Trim();
                _onSave?.Invoke(_settings);
                Close();
            };
            Controls.Add(saveBtn);

            UpdateQrCode();
        }

        public void UpdateStatus(string status)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => UpdateStatus(status)));
                return;
            }
            _statusLabel.Text = $"Status: {status}";
        }

        private void UpdateQrCode()
        {
            try
            {
                var payload = new
                {
                    server = _serverUrlBox.Text.Trim(),
                    secret = _pairingCodeBox.Text.Trim()
                };
                string qrText = JsonSerializer.Serialize(payload);

                using var qrGenerator = new QRCodeGenerator();
                using var qrCodeData = qrGenerator.CreateQrCode(qrText, QRCodeGenerator.ECCLevel.Q);
                using var qrCode = new QRCode(qrCodeData);
                var qrImage = qrCode.GetGraphic(5);

                var old = _qrPictureBox.Image;
                _qrPictureBox.Image = new Bitmap(qrImage);
                old?.Dispose();
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"QR generation error: {ex.Message}");
            }
        }
    }
}

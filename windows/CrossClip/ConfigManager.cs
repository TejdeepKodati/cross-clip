using System;
using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace CrossClip
{
    public class AppSettings
    {
        public string ServerUrl { get; set; } = "https://cross-clip-relay.workers.dev";
        public string MasterSecret { get; set; } = string.Empty;
        public bool ShowNotifications { get; set; } = false;
        public bool StartWithWindows { get; set; } = false;
    }

    public static class ConfigManager
    {
        private static readonly string AppDataFolder = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "cross-clip"
        );
        private static readonly string ConfigFilePath = Path.Combine(AppDataFolder, "config.json");
        private const string RunRegistryKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
        private const string AppRegistryName = "CrossClipSync";

        public static AppSettings LoadOrCreate()
        {
            try
            {
                if (!Directory.Exists(AppDataFolder))
                {
                    Directory.CreateDirectory(AppDataFolder);
                }

                if (File.Exists(ConfigFilePath))
                {
                    string json = File.ReadAllText(ConfigFilePath);
                    var settings = JsonSerializer.Deserialize<AppSettings>(json);
                    if (settings != null && !string.IsNullOrWhiteSpace(settings.MasterSecret))
                    {
                        settings.StartWithWindows = IsStartWithWindowsEnabled();
                        return settings;
                    }
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to load config: {ex.Message}");
            }

            // Create fresh settings with new 32-byte secret
            byte[] secretBytes = CryptoService.GenerateMasterSecret();
            var newSettings = new AppSettings
            {
                MasterSecret = CryptoService.EncodeBase32(secretBytes),
                ShowNotifications = false,
                StartWithWindows = false
            };

            Save(newSettings);
            return newSettings;
        }

        public static void Save(AppSettings settings)
        {
            try
            {
                if (!Directory.Exists(AppDataFolder))
                {
                    Directory.CreateDirectory(AppDataFolder);
                }

                string json = JsonSerializer.Serialize(settings, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(ConfigFilePath, json);
                SetStartWithWindows(settings.StartWithWindows);
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to save config: {ex.Message}");
            }
        }

        public static bool IsStartWithWindowsEnabled()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunRegistryKey, false);
                return key?.GetValue(AppRegistryName) != null;
            }
            catch
            {
                return false;
            }
        }

        public static void SetStartWithWindows(bool enable)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunRegistryKey, true);
                if (key == null) return;

                if (enable)
                {
                    string exePath = Environment.ProcessPath ?? Application.ExecutablePath;
                    key.SetValue(AppRegistryName, $"\"{exePath}\"");
                }
                else
                {
                    key.DeleteValue(AppRegistryName, false);
                }
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Failed to set registry run key: {ex.Message}");
            }
        }
    }
}

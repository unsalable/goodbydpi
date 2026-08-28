using System.IO;
using System.Text.Json;
using GoodbyeDpiUI.Models;

namespace GoodbyeDpiUI.Services;

/// <summary>Ayarlari %AppData%\GoodbyeDPI-UI\settings.json dosyasinda tutar.</summary>
public sealed class SettingsService
{
#if UITEST
    // Dogrulama derlemesi kurulu surumun ayarlarini ezmesin diye ayri klasor.
    private const string FolderName = "GoodbyeDPI-UI-uitest";
#else
    private const string FolderName = "GoodbyeDPI-UI";
#endif

    private static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), FolderName);

    private static readonly string FilePath = Path.Combine(Folder, "settings.json");

    public AppSettings Current { get; private set; } = new();

    public AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                // BOM'a karsi dayanikli ol: harici bir editorle kaydedilen dosya
                // basinda BOM tasiyabiliyor ve ayristirma hatasi sessizce
                // varsayilanlara donmeye yol aciyor.
                var json = File.ReadAllText(FilePath).TrimStart('﻿', '​').Trim();
                var loaded = JsonSerializer.Deserialize(json, AppSettingsJsonContext.Default.AppSettings);
                if (loaded is not null) Current = loaded;
            }
        }
        catch
        {
            // Bozuk ya da okunamayan dosya: varsayilanlarla devam et, kullaniciyi engelleme.
            Current = new AppSettings();
        }

        return Current;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Folder);
            var json = JsonSerializer.Serialize(Current, AppSettingsJsonContext.Default.AppSettings);

            // Atomik yazim: yarim kalmis dosya birakmamak icin once gecici dosyaya yaz.
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, json);
            File.Move(tmp, FilePath, overwrite: true);
        }
        catch
        {
            // Ayar kaydedilemediyse uygulama yine calisir; sessizce gec.
        }
    }
}

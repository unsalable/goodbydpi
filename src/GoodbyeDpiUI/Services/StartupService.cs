using System.Diagnostics;
using System.IO;
using System.Security;
using System.Security.Principal;
using System.Text;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Windows acilisinda otomatik baslatmayi yonetir.
///
/// Kayit defterindeki Run anahtari yerine Gorev Zamanlayici kullaniliyor: uygulama
/// yonetici yetkisi istedigi icin Run anahtari her acilista UAC penceresi cikarirdi.
/// "HighestAvailable" seviyeli bir gorev ise UAC sormadan yukseltilmis baslatir.
/// </summary>
public static class StartupService
{
    private const string TaskName = "GoodbyeDPI UI Autostart";

    /// <summary>Gorev tarafindan calistirilan uygulamaya verilen bayrak.</summary>
    public const string TrayArgument = "--tray";

    public static bool IsEnabled()
    {
        var (exit, _) = RunSchtasks($"/Query /TN \"{TaskName}\"");
        return exit == 0;
    }

    /// <summary>
    /// Surec yonetici olarak mi calisiyor?
    ///
    /// "HighestAvailable" seviyeli bir gorev olusturmak yukseltme ister. Bunu
    /// onceden kontrol ediyoruz cunku schtasks'in hata metni konsol kod sayfasinda
    /// dondugu icin Turkce karakterleri bozuk geliyor ve kullaniciya gosterilemiyor.
    /// </summary>
    private static bool IsElevated()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    public static bool Enable(out string? error)
    {
        error = null;
        var exePath = Environment.ProcessPath;

        if (string.IsNullOrEmpty(exePath))
        {
            error = "Uygulama yolu belirlenemedi.";
            return false;
        }

        if (!IsElevated())
        {
            error = "yonetici olarak calistirmak gerekiyor.";
            return false;
        }

        var xmlPath = Path.Combine(Path.GetTempPath(), "goodbyedpi-ui-task.xml");

        try
        {
            // schtasks /XML yalnizca UTF-16 kodlu dosya kabul eder.
            File.WriteAllText(xmlPath, BuildTaskXml(exePath), Encoding.Unicode);

            var (exit, output) = RunSchtasks($"/Create /TN \"{TaskName}\" /XML \"{xmlPath}\" /F");
            if (exit != 0)
            {
                error = Describe(exit, output);
                return false;
            }

            // schtasks bazen 0 dondurup gorevi olusturmayabiliyor; dogrulayalim.
            if (!IsEnabled())
            {
                error = "gorev olusturuldu gorunuyor ama Gorev Zamanlayici'da bulunamadi.";
                return false;
            }

            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            return false;
        }
        finally
        {
            try { File.Delete(xmlPath); } catch { /* gecici dosya, onemsiz */ }
        }
    }

    public static bool Disable(out string? error)
    {
        error = null;
        var (exit, output) = RunSchtasks($"/Delete /TN \"{TaskName}\" /F");

        // 1 = gorev zaten yok; bunu basarili sayiyoruz.
        if (exit is 0 or 1) return true;

        error = Describe(exit, output);
        return false;
    }

    /// <summary>
    /// schtasks ciktisini kullaniciya gosterilebilir hale getirir.
    ///
    /// Not: schtasks metni konsolun OEM kod sayfasinda yaziyor (Turkce'de CP857) ve
    /// .NET 8'de o kod sayfasini cozmek System.Text.Encoding.CodePages paketini
    /// gerektiriyor. Sifir bagimlilik hedefi icin metni oldugu gibi birakiyoruz,
    /// yani Turkce karakterler bozuk gorunebilir. Pratikteki tek yaygin sebep olan
    /// yukseltme eksikligi zaten Enable icinde onceden yakalanip duzgun bir
    /// mesajla bildiriliyor; bu yol sadece beklenmedik hatalar icin kaliyor.
    /// </summary>
    private static string Describe(int exitCode, string output)
    {
        var text = output.Trim();

        return string.IsNullOrEmpty(text)
            ? $"Gorev Zamanlayici {exitCode} kodunu dondurdu."
            : $"{text} (kod {exitCode})";
    }

    private static string BuildTaskXml(string exePath)
    {
        var user = SecurityElement.Escape($"{Environment.UserDomainName}\\{Environment.UserName}");
        var command = SecurityElement.Escape(exePath);
        var workDir = SecurityElement.Escape(Path.GetDirectoryName(exePath) ?? string.Empty);

        return $"""
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <!--
                Sema 1.2 (Windows 7+) kullaniliyor. DisallowStartOnRemoteAppSession ve
                UseUnifiedSchedulingEngine 1.3 ogeleridir; 1.2 icinde schtasks
                "Gorev XML'si beklenmeyen bir dugum iceriyor" hatasi verip gorevi
                hic olusturmaz. Bu yuzden bilerek yoklar.
              -->
              <RegistrationInfo>
                <Description>GoodbyeDPI UI - oturum acilisinda tepside sessizce baslar.</Description>
                <URI>\{TaskName}</URI>
              </RegistrationInfo>
              <Triggers>
                <LogonTrigger>
                  <Enabled>true</Enabled>
                  <UserId>{user}</UserId>
                  <Delay>PT5S</Delay>
                </LogonTrigger>
              </Triggers>
              <Principals>
                <Principal id="Author">
                  <UserId>{user}</UserId>
                  <LogonType>InteractiveToken</LogonType>
                  <RunLevel>HighestAvailable</RunLevel>
                </Principal>
              </Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <AllowHardTerminate>true</AllowHardTerminate>
                <StartWhenAvailable>true</StartWhenAvailable>
                <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
                <IdleSettings>
                  <StopOnIdleEnd>false</StopOnIdleEnd>
                  <RestartOnIdle>false</RestartOnIdle>
                </IdleSettings>
                <AllowStartOnDemand>true</AllowStartOnDemand>
                <Enabled>true</Enabled>
                <Hidden>false</Hidden>
                <RunOnlyIfIdle>false</RunOnlyIfIdle>
                <WakeToRun>false</WakeToRun>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Priority>7</Priority>
              </Settings>
              <Actions Context="Author">
                <Exec>
                  <Command>{command}</Command>
                  <Arguments>{TrayArgument}</Arguments>
                  <WorkingDirectory>{workDir}</WorkingDirectory>
                </Exec>
              </Actions>
            </Task>
            """;
    }

    private static (int ExitCode, string Output) RunSchtasks(string arguments)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "schtasks.exe",
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };

            using var p = Process.Start(psi);
            if (p is null) return (-1, "schtasks baslatilamadi.");

            var output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
            p.WaitForExit(10_000);
            return (p.HasExited ? p.ExitCode : -1, output);
        }
        catch (Exception ex)
        {
            return (-1, ex.Message);
        }
    }
}

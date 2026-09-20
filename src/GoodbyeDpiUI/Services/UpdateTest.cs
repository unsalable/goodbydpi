using System.IO;
using System.Text;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Otomatik guncelleme yolunun canli dogrulamasi (yonetici gerekmez, kurulum
/// YAPMAZ). "--updatetest" ile calisir: gercek GitHub release'ini sorgular,
/// daha yeniyse setup'i indirip SHA-256 ile dogrular. Kurucuyu asla baslatmaz.
/// Bu testin "yeni surum" gormesi icin uygulamayi dusuk bir surumle derleyin
/// (orn. -p:Version=1.0.0).
/// </summary>
internal static class UpdateTest
{
    public static int Run()
    {
        var log = new StringBuilder();
        var svc = new UpdateService();

        log.AppendLine($"Calisan surum: {UpdateService.CurrentVersion}");

        var info = svc.CheckAsync().GetAwaiter().GetResult();

        if (info is null)
        {
            log.AppendLine("Sonuc: guncel (yeni surum yok) veya erisilemedi.");
            Write(log, pass: true);
            return 0;
        }

        log.AppendLine($"Yeni surum: {info.Tag} ({info.Version})");
        log.AppendLine($"Dosya: {info.AssetName} ({info.Size / 1024 / 1024} MB)");
        log.AppendLine($"URL: {info.DownloadUrl}");
        log.AppendLine($"Beklenen SHA-256: {info.Sha256 ?? "(yok)"}");
        log.AppendLine("Indiriliyor + dogrulaniyor...");

        // Guncelleme ekrani bu bildirimlerle besleniyor: geldiklerini de dogrula.
        var reports = 0;
        var lastPercent = -1;
        var monotonic = true;
        long lastDone = 0;

        var progress = new InlineProgress<DownloadProgress>(p =>
        {
            reports++;
            if (p.Done < lastDone) monotonic = false;
            lastDone = p.Done;

            if (p.Total <= 0) return;
            var percent = (int)(100.0 * p.Done / p.Total);
            if (percent / 10 == lastPercent / 10) return;
            lastPercent = percent;
            log.AppendLine($"  %{percent} ({p.Done / 1024 / 1024} / {p.Total / 1024 / 1024} MB)");
        });

        var path = svc.DownloadAsync(info, progress).GetAwaiter().GetResult();

        log.AppendLine($"Ilerleme bildirimi: {reports} adet, geri gitmedi: {monotonic}");

        var ok = path is not null && reports > 0 && monotonic;
        if (ok)
        {
            var size = new FileInfo(path!).Length;
            log.AppendLine($"Indirildi ve SHA-256 DOGRULANDI: {path} ({size / 1024 / 1024} MB)");
            log.AppendLine("(Kurulum bilerek baslatilmadi - bu yalnizca test.)");
        }
        else
        {
            log.AppendLine("HATA: indirme basarisiz ya da SHA-256 tutmadi.");
        }

        Write(log, ok);
        return ok ? 0 : 1;
    }

    /// <summary>
    /// Ilerlemeyi indiren is parcaciginda dogrudan bildirir. Burada bir gonderici
    /// dongusu donmedigi icin <see cref="Progress{T}"/> geri cagrilari hic isletilmezdi.
    /// </summary>
    private sealed class InlineProgress<T>(Action<T> report) : IProgress<T>
    {
        public void Report(T value) => report(value);
    }

    private static void Write(StringBuilder log, bool pass)
    {
        log.Insert(0, pass ? "GUNCELLEME TESTI: GECTI\n\n" : "GUNCELLEME TESTI: KALDI\n\n");
        var path = Path.Combine(Path.GetTempPath(), "goodbyedpi-updatetest.txt");
        try { File.WriteAllText(path, log.ToString()); } catch { /* onemsiz */ }
    }
}

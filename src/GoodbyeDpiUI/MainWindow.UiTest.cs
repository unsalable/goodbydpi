#if UITEST
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using ShapePath = System.Windows.Shapes.Path;

namespace GoodbyeDpiUI;

/// <summary>Yalnizca dogrulama derlemesi: acilir liste, kaydirma ve animasyon regresyon testi.</summary>
public partial class MainWindow
{
    /// <summary>
    /// "Liste acikken tekerlek govdeyi kaydiriyor, liste kaymiyor ve acilir panel
    /// yerinde takili kaliyor" hatasinin regresyon kontrolu; ayrica acilma / kapanma
    /// animasyonunun gercekten ara karelerden gectigini olcer.
    ///
    /// Sentetik tekerlek WPF girdi yoneticisinin yaptigini taklit eder (once Preview,
    /// islenmediyse kabarcik). realInput verilirse ayrica gercek imleci liste ustune
    /// goturup isletim sistemi tekerlegi gonderir, sonra imleci geri birakir.
    /// </summary>
    public void RunDropdownTest(string prefix, bool realInput)
    {
        ContentRendered += async (_, _) =>
        {
            var log = new StringBuilder();
            var failures = 0;

            void Check(bool ok, string what)
            {
                if (!ok) failures++;
                log.AppendLine((ok ? "GECTI  " : "KALDI  ") + what);
            }

            // Gercek girdi pencere ustte degilse baska pencereye gider.
            Topmost = true;
            Activate();

            SettingsToggle.IsChecked = true;
            await Task.Delay(900);

            var combo = FindDescendant<ComboBox>(SettingsPanel)!;
            var popup = (Popup)combo.Template.FindName("PART_Popup", combo);
            var card = (FrameworkElement)popup.Child;
            var arrow = FindDescendant<ShapePath>(combo)!;

            log.AppendLine($"govde kaydirilabilir={BodyScroll.ScrollableHeight:F0}");

            // ---------------------------------------------- acilma animasyonu
            combo.IsDropDownOpen = true;
            await Task.Delay(45);
            var early = (Opacity: card.Opacity, Scale: CardScale(card));
            await Task.Delay(700);
            var settled = (Opacity: card.Opacity, Scale: CardScale(card));

            log.AppendLine($"acilma: 45ms opaklik={early.Opacity:F2} olcek={early.Scale:F3}; " +
                           $"750ms opaklik={settled.Opacity:F2} olcek={settled.Scale:F3} ok={ArrowAngle(arrow):F0}");
            Check(early.Opacity < 0.95 || early.Scale < 0.995, "acilma ara kareden geciyor (aninda belirmiyor)");
            Check(Math.Abs(settled.Opacity - 1) < 0.01 && Math.Abs(settled.Scale - 1) < 0.005, "acilma sonunda panel tam boy ve opak");
            Check(Math.Abs(ArrowAngle(arrow) - 180) < 1, "ok 180 derece dondu");

            var list = FindDescendant<ScrollViewer>(card)!;
            var item = (ComboBoxItem)combo.ItemContainerGenerator.ContainerFromIndex(2);
            log.AppendLine($"liste kaydirilabilir={list.ScrollableHeight:F0} ofset={list.VerticalOffset:F0}");
            Check(list.ScrollableHeight > 20, "liste piksel bazli kayiyor (oge bazli degil)");
            SaveScreen($"{prefix}-1-acik.png");

            var anchorBefore = PopupGap(combo, popup);
            var bodyBefore = BodyScroll.VerticalOffset;

            // Geri ziplama takibi: liste her kaydiginda ofset kaydediliyor.
            var steps = new List<double>();
            list.ScrollChanged += (_, e) => steps.Add(e.VerticalOffset);

            // ---------------------------- 1) sentetik tekerlek liste ogesinin ustunde
            RaiseWheel(item, -120);
            await Task.Delay(35);
            var glideMid = list.VerticalOffset;
            RaiseWheel(item, -120);
            await Task.Delay(40);
            RaiseWheel(item, -120);
            await Task.Delay(700);

            log.AppendLine($"sentetik: ara ofset={glideMid:F1} son ofset={list.VerticalOffset:F1} govde {bodyBefore:F0}->{BodyScroll.VerticalOffset:F0}");
            Check(list.VerticalOffset > 1, "sentetik tekerlek listeyi kaydirdi");
            Check(glideMid > 0.5 && glideMid < list.VerticalOffset - 0.5, "liste suzulerek kaydi (tek karede ziplamadi)");
            Check(Math.Abs(BodyScroll.VerticalOffset - bodyBefore) < 0.5, "sentetik tekerlek govdeyi kaydirmadi");
            Check(Math.Abs(PopupGap(combo, popup) - anchorBefore) < 1, "acilir panel kutunun altinda kaldi");
            SaveScreen($"{prefix}-2-liste-kaydi.png");

            // ------------- 2) liste acikken kutunun ustunde tekerlek (imlec kutuda yakali)
            RaiseWheel(combo, -120);
            await Task.Delay(500);
            Check(Math.Abs(BodyScroll.VerticalOffset - bodyBefore) < 0.5, "liste acikken kutu ustunde tekerlek govdeyi kaydirmadi");
            Check(combo.IsDropDownOpen, "liste acik kaldi");

            // ------------------- 3) liste acikken kutu yer degistirirse panel pesinden gider
            var spacer = new Border { Height = 30 };
            SettingsContent.Children.Insert(0, spacer);
            await Task.Delay(400);
            var gapAfterShift = PopupGap(combo, popup);

            // Ikinci kaydirma: duzeltme her seferinde birikmemeli.
            spacer.Height = 60;
            await Task.Delay(400);
            var gapAfterSecond = PopupGap(combo, popup);

            log.AppendLine($"kutu kaydi: bosluk {anchorBefore:F1} -> {gapAfterShift:F1} -> {gapAfterSecond:F1}");
            Check(Math.Abs(gapAfterShift - anchorBefore) < 1, "kutu 30px kayinca panel de birlikte kaydi");
            Check(Math.Abs(gapAfterSecond - anchorBefore) < 1, "ikinci kaymada da panel kutuda kaldi");
            SaveScreen($"{prefix}-3-kutu-kaydi.png");
            SettingsContent.Children.Remove(spacer);
            await Task.Delay(300);

            if (realInput)
            {
                GetCursorPos(out var saved);
                try
                {
                    // 4) Gercek tekerlek, imlec listenin USTUNDE. Liste imlecin altinda kayarken
                    // ComboBox altina gelen ogeye odak veriyor, odak da ogeyi gorunur alana cekmek
                    // isteyip kaydirmayi geri ziplatiyordu; ofsetler geri gitmemeli.
                    list.ScrollToTop();
                    await Task.Delay(350);
                    steps.Clear();

                    for (var i = 0; i < 3; i++)
                    {
                        var p = item.PointToScreen(new Point(item.ActualWidth / 2, item.ActualHeight / 2));
                        SetCursorPos((int)p.X, (int)p.Y);
                        await Task.Delay(120);
                        mouse_event(MouseEventfWheel, 0, 0, -120, 0);
                        await Task.Delay(60);
                    }

                    await Task.Delay(700);

                    var backSteps = steps.Zip(steps.Skip(1)).Count(pair => pair.Second < pair.First - 0.6);
                    log.AppendLine($"gercek: liste ofset={list.VerticalOffset:F0} kare={steps.Count} geri={backSteps} " +
                                   $"govde {bodyBefore:F0}->{BodyScroll.VerticalOffset:F0}");
                    Check(list.VerticalOffset > 1, "gercek tekerlek listeyi kaydirdi");
                    Check(backSteps == 0, "imlec listenin ustundeyken kaydirma geri ziplamadi");
                    Check(Math.Abs(BodyScroll.VerticalOffset - bodyBefore) < 0.5, "gercek tekerlek govdeyi kaydirmadi");
                    Check(Math.Abs(PopupGap(combo, popup) - anchorBefore) < 1, "gercek tekerlekten sonra panel yerinde");
                    SaveScreen($"{prefix}-4-gercek-tekerlek.png");
                }
                finally
                {
                    SetCursorPos(saved.X, saved.Y);
                }
            }

            // ---------------------------------------------- kapanma animasyonu
            combo.IsDropDownOpen = false;
            await Task.Delay(60);
            var closing = (Open: popup.IsOpen, Opacity: card.Opacity, HitTest: card.IsHitTestVisible);
            await Task.Delay(450);
            log.AppendLine($"kapanma: 60ms acik={closing.Open} opaklik={closing.Opacity:F2}; 510ms acik={popup.IsOpen} ok={ArrowAngle(arrow):F0}");
            Check(closing.Open && closing.Opacity < 0.95, "kapanirken panel solarak kayboluyor");
            Check(!closing.HitTest, "solan panel tiklama almiyor");
            Check(!popup.IsOpen, "animasyon bitince panel kapandi");
            Check(Math.Abs(ArrowAngle(arrow)) < 1, "ok geri dondu");

            // ---------------------------- 5) liste kapaliyken tekerlek govdeyi kaydirmali
            BodyScroll.ScrollToTop();
            await Task.Delay(200);
            var closedBefore = BodyScroll.VerticalOffset;
            RaiseWheel(combo, -240);
            await Task.Delay(35);
            var bodyMid = BodyScroll.VerticalOffset;
            await Task.Delay(600);
            log.AppendLine($"kapali: govde {closedBefore:F0} -> ara {bodyMid:F1} -> {BodyScroll.VerticalOffset:F0}");
            Check(BodyScroll.VerticalOffset > closedBefore + 1, "liste kapaliyken tekerlek govdeyi kaydirdi");
            Check(bodyMid > closedBefore + 0.5 && bodyMid < BodyScroll.VerticalOffset - 0.5, "govde suzulerek kaydi");

            // ------------------- 6) klavye: asagi ok vurguyu ilerletip listeyi kaydirmali
            combo.IsDropDownOpen = true;
            await Task.Delay(600);
            var keyboardBefore = list.VerticalOffset;
            for (var i = 0; i < 8; i++)
            {
                combo.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, PresentationSource.FromVisual(this), 0, Key.Down)
                {
                    RoutedEvent = Keyboard.KeyDownEvent,
                });
                await Task.Delay(60);
            }

            await Task.Delay(400);
            log.AppendLine($"klavye: liste {keyboardBefore:F0} -> {list.VerticalOffset:F0}");
            Check(list.VerticalOffset > keyboardBefore + 1, "asagi okla gezinince liste kaydi");
            combo.IsDropDownOpen = false;
            await Task.Delay(400);

            // --------------- 7) secili oge listenin disindaysa acilista gorunur alana gelmeli
            if (Vm is { } vm)
            {
                var previousIsp = vm.SelectedIsp;
                vm.SelectedIsp = Models.IspProfile.VodafoneMobil;
                await Task.Delay(300);

                combo.IsDropDownOpen = true;
                await Task.Delay(700);
                log.AppendLine($"secili oge: liste ofset={list.VerticalOffset:F0}");
                Check(list.VerticalOffset > 20, "acilista secili oge gorunur alana getirildi");
                combo.IsDropDownOpen = false;
                await Task.Delay(400);

                vm.SelectedIsp = previousIsp;
                await Task.Delay(300);
            }

            // ------------------- 8) ayni liste tekrar acilip hemen kapaninca takilmamali
            combo.IsDropDownOpen = true;
            await Task.Delay(30);
            combo.IsDropDownOpen = false;
            await Task.Delay(30);
            combo.IsDropDownOpen = true;
            await Task.Delay(700);
            Check(popup.IsOpen && Math.Abs(card.Opacity - 1) < 0.01 && card.IsHitTestVisible, "hizli ac-kapa-ac sonunda panel acik ve tiklanabilir");
            combo.IsDropDownOpen = false;
            await Task.Delay(500);
            Check(!popup.IsOpen, "hizli ac-kapa-ac sonrasi kapandi");

            log.AppendLine(failures == 0 ? "SONUC: tum kontroller gecti" : $"SONUC: {failures} kontrol kaldi");
            System.IO.File.WriteAllText(prefix + "-sonuc.txt", log.ToString());
            Application.Current.Shutdown(failures == 0 ? 0 : 1);
        };
    }

    /// <summary>
    /// Adlandirilmis ozel profillerin uctan uca kontrolu: yeni profil olusturma,
    /// adlandirma, ayar degistirince listedeki ozetin tazelenmesi, silme ve her seyin
    /// ayar dosyasina yazilmasi. Ayni akis DNS girisleri icin de kosuluyor.
    ///
    /// Her adimdan sonra ACILIR KUTUNUN secili ogesi de kontrol ediliyor: liste her
    /// tazelendiginde yeniden uretildigi icin secimin kutuda da korunmasi sart.
    /// </summary>
    public void RunProfileTest(string prefix)
    {
        ContentRendered += async (_, _) =>
        {
            if (Vm is not { } vm) return;

            var log = new StringBuilder();
            var failures = 0;

            void Check(bool ok, string what)
            {
                if (!ok) failures++;
                log.AppendLine((ok ? "GECTI  " : "KALDI  ") + what);
            }

            SettingsToggle.IsChecked = true;
            await Task.Delay(800);

            // Bilinen baslangic: Genel saglayici (yontem listesi sabit).
            vm.SelectedIsp = Models.IspProfile.General;
            await Task.Delay(300);

            var startMethods = vm.NativeProfiles.Count;
            var startDns = vm.DnsProfiles.Count;
            log.AppendLine($"baslangic: yontem={startMethods} dns={startDns}");

            // ---------------------------------------- 1) yeni profil olustur
            vm.AddCustomProfileCommand.Execute(null);
            await Task.Delay(500);

            var created = vm.SelectedNativeProfile;
            log.AppendLine($"olusan: id={created.Id} ad=\"{created.Name}\" ozet=\"{created.Description}\"");

            Check(Models.NativeProfile.IsCustomId(created.Id), "yeni ozel profil olustu ve secildi");
            Check(Equals(MethodBox.SelectedItem, created), "kutu yeni profili gosteriyor");
            Check(vm.NativeProfiles.Count == startMethods + 1,
                $"liste bir uzadi ({startMethods} -> {vm.NativeProfiles.Count})");
            Check(vm.ShowCustomNative, "ozel ayar paneli acildi");
            SaveRender($"{prefix}-1-yeni-profil.png");

            // ---------------------------------------- 2) adlandirma listeye yansimali
            vm.CustomProfileName = "Gece profili";
            await Task.Delay(400);

            Check(vm.SelectedNativeProfile.Name == "Gece profili", "profil adi degisti");
            Check(vm.NativeProfiles.Any(p => p.Name == "Gece profili"), "yeni ad listede gorunuyor");
            Check(Equals(MethodBox.SelectedItem, vm.SelectedNativeProfile), "ad degisince secim korundu");
            Check(BoxName(MethodBox) == "Gece profili", $"kutuda yazan ad tazelendi (\"{BoxName(MethodBox)}\")");

            // ------------------------- 3) ayar degisince listedeki ozet de tazelenmeli
            vm.NativeBlockQuic = false;
            await Task.Delay(300);
            var summary = vm.SelectedNativeProfile.Description;
            log.AppendLine($"ozet: \"{summary}\"");
            Check(!summary.Contains("QUIC"), "QUIC kapatilinca ozetten dustu");
            Check(Equals(MethodBox.SelectedItem, vm.SelectedNativeProfile), "ayar degisince secim korundu");
            Check(BoxDescription(MethodBox) == summary, "kutunun gosterdigi ozet de tazelendi");

            // ------------------------- 4) ikinci profil: ad ve kimlik cakismamali
            vm.AddCustomProfileCommand.Execute(null);
            await Task.Delay(500);

            var second = vm.SelectedNativeProfile;
            var customIds = vm.NativeProfiles.Where(p => Models.NativeProfile.IsCustomId(p.Id)).ToList();
            log.AppendLine($"ozel profiller: {string.Join(" | ", customIds.Select(p => p.Name))}");

            Check(second.Id != created.Id, "ikinci profil ayri kimlik aldi");
            Check(Equals(MethodBox.SelectedItem, second), "kutu ikinci profili gosteriyor");
            Check(customIds.Count >= 3, $"birden fazla ozel profil listeleniyor ({customIds.Count})");
            Check(customIds.Select(p => p.Name).Distinct().Count() == customIds.Count, "adlar cakismiyor");
            Check(second.Build().BlockQuic == false, "yeni profil kaynak profilin ayarlarini kopyaladi");
            SaveRender($"{prefix}-2-ikinci-profil.png");

            // ---- 4b) saglayici KUTUDAN degistirilince yontem listesi ve secim tazelenmeli
            //          (listeyi degistiren tek baglama surumlu yol bu.)
            var ispIndex = vm.Isps.ToList().FindIndex(i => i.Id == Models.IspProfile.Superonline.Id);
            IspBox.SelectedIndex = ispIndex;
            await Task.Delay(500);

            log.AppendLine($"saglayici: {vm.SelectedIsp.Name} -> yontem {vm.SelectedNativeProfile.Name}, " +
                           $"liste: {string.Join(" | ", vm.NativeProfiles.Select(p => p.Name))}");

            Check(vm.SelectedIsp == Models.IspProfile.Superonline, "kutudan saglayici degisti");
            Check(Equals(IspBox.SelectedItem, vm.SelectedIsp), "saglayici kutusu secimi korudu");
            Check(vm.SelectedNativeProfile == Models.IspProfile.Superonline.Recommended,
                "yontem saglayicinin onerisine gecti");
            Check(Equals(MethodBox.SelectedItem, vm.SelectedNativeProfile), "yontem kutusu da onerilene gecti");
            Check(vm.NativeProfiles.Any(p => p.Name == "Gece profili"), "ozel profiller listede kaldi");
            Check(vm.NativeProfiles.Contains(Models.NativeProfile.Md5Sig), "saglayicinin yontemleri geldi");

            vm.SelectedIsp = Models.IspProfile.General;
            await Task.Delay(400);
            Check(Equals(IspBox.SelectedItem, vm.SelectedIsp), "genele donunce kutu da dondu");

            // Silme adimlari onerilen yonteme donuyor; secimi yeniden ozel profile al.
            vm.SelectedNativeProfile = second;
            await Task.Delay(300);

            // ------------------------- 5) ayar dosyasina gercekten yaziliyor mu
            var saved = new Services.SettingsService().Load();
            Check(saved.CustomProfiles.Any(p => p.Name == "Gece profili"), "profil ayar dosyasina yazildi");
            Check(saved.NativeProfile == second.Id, "secim ayar dosyasina yazildi");
            log.AppendLine($"dosyadaki profiller: {string.Join(" | ", saved.CustomProfiles.Select(p => p.Name))}");

            // ------------------------- 6) DNS tarafi: yeni giris + adres
            vm.AddCustomDnsCommand.Execute(null);
            await Task.Delay(500);

            Check(Models.DnsProfile.IsCustomId(vm.SelectedDns.Id), "yeni DNS girisi olustu ve secildi");
            Check(Equals(DnsBox.SelectedItem, vm.SelectedDns), "DNS kutusu yeni girisi gosteriyor");
            Check(vm.ShowCustomDns, "ozel DNS paneli acildi");
            Check(vm.DnsProfiles.Count == startDns + 1, $"DNS listesi bir uzadi ({startDns} -> {vm.DnsProfiles.Count})");

            vm.CustomDnsName = "Ev DNS";
            vm.DnsCustomV4 = "9.9.9.9";
            vm.DnsCustomV4Port = "5353";
            await Task.Delay(400);

            log.AppendLine($"dns: ad=\"{vm.SelectedDns.Name}\" arg=\"{vm.SelectedDns.Arguments}\" uyari=\"{vm.DnsWarning}\"");
            Check(vm.SelectedDns.Name == "Ev DNS", "DNS girisi adlandirildi");
            Check(BoxName(DnsBox) == "Ev DNS", $"DNS kutusunda yazan ad tazelendi (\"{BoxName(DnsBox)}\")");
            Check(vm.SelectedDns.Arguments == "--dns-addr 9.9.9.9 --dns-port 5353", "adres ve port uygulandi");
            Check(!vm.HasDnsWarning, "gecerli adres uyari uretmedi");
            Check(Equals(DnsBox.SelectedItem, vm.SelectedDns), "adres degisince DNS secimi korundu");

            vm.DnsCustomV4 = "999.1.1.1";
            await Task.Delay(300);
            Check(vm.HasDnsWarning, "gecersiz adres uyari verdi");
            vm.DnsCustomV4 = "9.9.9.9";
            await Task.Delay(300);
            SaveRender($"{prefix}-3-ozel-dns.png");

            // ------------------------- 7) silme: secim onerilene donmeli
            vm.DeleteCustomDnsCommand.Execute(null);
            await Task.Delay(400);
            Check(!vm.ShowCustomDns, "DNS girisi silinince panel kapandi");
            Check(vm.DnsProfiles.Count == startDns, $"DNS listesi eski boyuna dondu ({vm.DnsProfiles.Count})");

            vm.DeleteCustomProfileCommand.Execute(null);
            await Task.Delay(400);
            Check(!vm.ShowCustomNative, "profil silinince ozel panel kapandi");
            Check(vm.SelectedNativeProfile == vm.SelectedIsp.Recommended, "silince onerilen yonteme donuldu");
            Check(Equals(MethodBox.SelectedItem, vm.SelectedNativeProfile), "silme sonrasi kutu da guncellendi");

            // Testin yarattigi profil geriye kalmasin.
            vm.SelectedNativeProfile = created;
            await Task.Delay(300);
            vm.DeleteCustomProfileCommand.Execute(null);
            await Task.Delay(300);
            Check(vm.NativeProfiles.Count == startMethods, $"liste baslangic boyuna dondu ({vm.NativeProfiles.Count})");
            SaveRender($"{prefix}-4-temizlendi.png");

            log.AppendLine(failures == 0 ? "SONUC: tum kontroller gecti" : $"SONUC: {failures} kontrol kaldi");
            System.IO.File.WriteAllText(prefix + "-sonuc.txt", log.ToString());
            Application.Current.Shutdown(failures == 0 ? 0 : 1);
        };
    }

    /// <summary>
    /// Guncelleme ekraninin her adimini sirayla gosterip kaydeder: indirme yuzdesi,
    /// dogrulama, kurulum, hata ve kurulum sonrasi "guncellendi" seridi.
    /// </summary>
    public void RunUpdateShot(string prefix)
    {
        ContentRendered += async (_, _) =>
        {
            if (Vm is not { } vm) return;

            const long Total = 61_800_000;
            var log = new StringBuilder();

            async Task Step(string name, ViewModels.UpdateStage stage, long done)
            {
                vm.PreviewUpdate(stage, done, Total);
                await Task.Delay(700);
                UpdateLayout();
                SaveRender($"{prefix}-{name}.png");
                log.AppendLine($"{name}: pencere={ActualHeight:F0} baslik=\"{vm.UpdateTitle}\" " +
                               $"surum=\"{vm.UpdateVersionText}\" oran={vm.UpdateFraction:F2} " +
                               $"ilerleme=\"{vm.UpdateProgressText}\" donuyor={vm.UpdateBusy}");
            }

            await Task.Delay(600);

            await Step("1-indiriliyor", ViewModels.UpdateStage.Downloading, 14_200_000);
            await Step("2-indiriliyor-ileri", ViewModels.UpdateStage.Downloading, 47_500_000);
            await Step("3-dogrulaniyor", ViewModels.UpdateStage.Verifying, Total);
            await Step("4-kuruluyor", ViewModels.UpdateStage.Installing, Total);
            await Step("5-hata", ViewModels.UpdateStage.Failed, 0);

            // Kart kapatilinca govdede kalan serit.
            vm.LaterUpdateCommand.Execute(null);
            await Task.Delay(700);
            SaveRender($"{prefix}-6-serit.png");
            log.AppendLine($"serit: gorunur={vm.UpdateAvailable} metin=\"{vm.UpdateBannerText}\" " +
                           $"ekran={vm.ShowUpdateScreen}");

            // Kurulum sonrasi ilk acilis bildirimi.
            vm.PreviewUpdateDone();
            await Task.Delay(700);
            SaveRender($"{prefix}-7-guncellendi.png");
            log.AppendLine($"guncellendi: gorunur={vm.HasUpdateDone} metin=\"{vm.UpdateDoneText}\"");

            System.IO.File.WriteAllText(prefix + "-olcu.txt", log.ToString());
            Application.Current.Shutdown();
        };
    }

    /// <summary>Kutunun GERCEKTEN tuttugu nesnenin adi (baglama bunu gosteriyor).</summary>
    private static string? BoxName(ComboBox box) => box.SelectedItem switch
    {
        Models.NativeProfile p => p.Name,
        Models.DnsProfile d => d.Name,
        _ => null,
    };

    private static string? BoxDescription(ComboBox box) => box.SelectedItem switch
    {
        Models.NativeProfile p => p.Description,
        Models.DnsProfile d => d.Description,
        _ => null,
    };

    private static double CardScale(FrameworkElement card) =>
        card.RenderTransform is TransformGroup { Children: [ScaleTransform scale, ..] } ? scale.ScaleX : 1;

    private static double ArrowAngle(UIElement arrow) =>
        arrow.RenderTransform is TransformGroup group
            ? group.Children.OfType<RotateTransform>().FirstOrDefault()?.Angle ?? 0
            : 0;

    /// <summary>Acilir panelin ust kenari ile kutunun alt kenari arasindaki dikey mesafe (ekran pikseli).</summary>
    private static double PopupGap(ComboBox combo, Popup popup)
    {
        if (popup.Child is not FrameworkElement child || !child.IsVisible) return double.NaN;
        return child.PointToScreen(new Point(0, 0)).Y - combo.PointToScreen(new Point(0, combo.ActualHeight)).Y;
    }

    /// <summary>WPF girdi yoneticisinin sirasi: once Preview, islenmediyse kabarcik olayi.</summary>
    private static void RaiseWheel(UIElement target, int delta)
    {
        var preview = new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, delta)
        {
            RoutedEvent = UIElement.PreviewMouseWheelEvent,
            Source = target,
        };
        target.RaiseEvent(preview);
        if (preview.Handled) return;

        target.RaiseEvent(new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, delta)
        {
            RoutedEvent = UIElement.MouseWheelEvent,
            Source = target,
        });
    }

    /// <summary>
    /// Ekranin pencere bolgesini kaydeder. Acilir panel ayri bir pencere oldugu icin
    /// RenderTargetBitmap onu gostermez; burada kullanicinin gordugu bilesik goruntu alinir.
    /// </summary>
    private void SaveScreen(string path)
    {
        var topLeft = PointToScreen(new Point(0, 0));
        var bottomRight = PointToScreen(new Point(ActualWidth, ActualHeight));
        var width = (int)(bottomRight.X - topLeft.X);
        var height = (int)(bottomRight.Y - topLeft.Y);

        using var bmp = new System.Drawing.Bitmap(width, height);
        using (var g = System.Drawing.Graphics.FromImage(bmp))
            g.CopyFromScreen((int)topLeft.X, (int)topLeft.Y, 0, 0, new System.Drawing.Size(width, height));
        bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
    }

    private const uint MouseEventfWheel = 0x0800;

    [StructLayout(LayoutKind.Sequential)]
    private struct CursorPoint
    {
        public int X, Y;
    }

    [DllImport("user32.dll")]
    private static extern bool SetCursorPos(int x, int y);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out CursorPoint point);

    [DllImport("user32.dll")]
    private static extern void mouse_event(uint flags, int dx, int dy, int data, nint extraInfo);
}
#endif

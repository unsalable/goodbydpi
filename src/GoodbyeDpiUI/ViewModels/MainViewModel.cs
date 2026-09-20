using System.Globalization;
using System.IO;
using System.Windows;
using GoodbyeDpiUI.Models;
using GoodbyeDpiUI.Services;

namespace GoodbyeDpiUI.ViewModels;

/// <summary>Guncelleme ekraninin icinde bulundugu adim.</summary>
public enum UpdateStage
{
    /// <summary>Guncelleme ile ilgili bir sey olmuyor.</summary>
    None,

    /// <summary>GitHub sorgulaniyor (ekran gosterilmez, sessiz adim).</summary>
    Checking,

    /// <summary>Setup dosyasi iniyor.</summary>
    Downloading,

    /// <summary>Inen dosyanin SHA-256'si kontrol ediliyor.</summary>
    Verifying,

    /// <summary>Dosya indi ve dogrulandi, kurulmayi bekliyor.</summary>
    Ready,

    /// <summary>Kurucu baslatiliyor, uygulama kapanmak uzere.</summary>
    Installing,

    /// <summary>Bir adim tamamlanamadi; kullaniciya sebebi gosteriliyor.</summary>
    Failed,
}

public sealed class MainViewModel : ObservableObject
{
    private readonly DpiController _dpi;
    private readonly SettingsService _settings;
    private readonly ThemeService _theme;
    private readonly UpdateService _updates;

    private string _statusText = "Kapalı";
    private string _statusDetail = string.Empty;
    private bool _isBusy;
    private bool _areAnimationsEnabled = true;
    private string _startupWarning = string.Empty;

    // Acilir liste kaynaklari onbellekte tutulur: her cagrida yeniden uretilseler
    // ComboBox'in secili ogesi her yerlesimde degisir ve secim kaybolurdu.
    private IReadOnlyList<NativeProfile> _nativeProfiles = [];
    private IReadOnlyList<DnsProfile> _dnsProfiles = [];

    /// <summary>Ozel profil secili degilken ayar baglamalarinin okudugu bos yapilandirma.</summary>
    private readonly NativeDpiConfig _noProfile = new();

    // Guncelleme durumu
    private UpdateStage _updateStage = UpdateStage.None;
    private UpdateInfo? _updateInfo;
    private string? _pendingSetup;
    private string _updateError = string.Empty;
    private double _updateFraction;
    private string _updateProgressText = string.Empty;
    private string _updateDoneText = string.Empty;
    private bool _updateScreenVisible;
    private bool _updateDeferred;
    private CancellationTokenSource? _updateCancel;

    /// <summary>App tarafindan atanir: guncelleme kuruluma gecerken uygulamadan cikar.</summary>
    public Action? RequestShutdown { get; set; }

    /// <summary>
    /// App tarafindan atanir: guncellemeden sonra uygulama bu argumanlarla yeniden acilir
    /// (tepside baslatildiysa yine tepside acilsin diye).
    /// </summary>
    public string? RelaunchArguments { get; set; }

    public MainViewModel(DpiController dpi, SettingsService settings, ThemeService theme, UpdateService updates)
    {
        _dpi = dpi;
        _settings = settings;
        _theme = theme;
        _updates = updates;

        _dpi.StateChanged += OnStateChanged;

        RebuildNativeProfiles();
        RebuildDnsProfiles();
        ReportFinishedUpdate();

        ToggleConnectionCommand = new RelayCommand(() => _ = ToggleAsync(), () => !IsBusy);
        ApplyUpdateCommand = new RelayCommand(() => _ = StartUpdateAsync());
        LaterUpdateCommand = new RelayCommand(DeferUpdate);
        DismissUpdateDoneCommand = new RelayCommand(() => UpdateDoneText = string.Empty);

        ResetCustomNativeCommand = new RelayCommand(ResetCustomNative);
        AddCustomProfileCommand = new RelayCommand(AddCustomProfile);
        DeleteCustomProfileCommand = new RelayCommand(DeleteCustomProfile);
        AddCustomDnsCommand = new RelayCommand(AddCustomDns);
        DeleteCustomDnsCommand = new RelayCommand(DeleteCustomDns);

        UpdateStatus();
    }

    // ------------------------------------------------------------- durum

    public ConnectionState State => _dpi.State;

    /// <summary>Kapali (bosta): arayuzde notr renkli katmanlar bu durumda gorunur.</summary>
    public bool IsIdle => State == ConnectionState.Disconnected;

    public bool IsConnected => State == ConnectionState.Connected;
    public bool IsConnecting => State == ConnectionState.Connecting;
    public bool IsFailed => State == ConnectionState.Failed;

    public string StatusText
    {
        get => _statusText;
        private set => SetField(ref _statusText, value);
    }

    public string StatusDetail
    {
        get => _statusDetail;
        private set => SetField(ref _statusDetail, value);
    }

    public bool IsBusy
    {
        get => _isBusy;
        private set
        {
            if (SetField(ref _isBusy, value)) ToggleConnectionCommand.RaiseCanExecuteChanged();
        }
    }

    public bool AreAnimationsEnabled
    {
        get => _areAnimationsEnabled;
        set => SetField(ref _areAnimationsEnabled, value);
    }

    // ------------------------------------------------------------ tema / genel ayarlar

    public bool IsDark
    {
        get => _theme.IsDark;
        set
        {
            if (_theme.IsDark == value) return;
            _theme.Set(value);
            _settings.Current.DarkMode = value;
            _settings.Save();
            OnPropertyChanged();
        }
    }

    public bool RunAtStartup
    {
        get => _settings.Current.RunAtStartup;
        set
        {
            if (_settings.Current.RunAtStartup == value) return;

            var ok = value
                ? StartupService.Enable(out var error)
                : StartupService.Disable(out error);

            if (!ok)
            {
                ReportStartupProblem(error);
                OnPropertyChanged();
                return;
            }

            StartupWarning = string.Empty;
            _settings.Current.RunAtStartup = value;
            _settings.Save();
            OnPropertyChanged();
        }
    }

    public bool AutoConnect
    {
        get => _settings.Current.AutoConnect;
        set
        {
            if (_settings.Current.AutoConnect == value) return;
            _settings.Current.AutoConnect = value;
            _settings.Save();
            OnPropertyChanged();
        }
    }

    public bool AutoUpdate
    {
        get => _settings.Current.AutoUpdate;
        set
        {
            if (_settings.Current.AutoUpdate == value) return;
            _settings.Current.AutoUpdate = value;
            _settings.Save();
            OnPropertyChanged();
        }
    }

    // ------------------------------------------------------------ altyapi secimi

    /// <summary>Kendi motorumuz mu kullaniliyor (aksi halde hazir goodbyedpi.exe).</summary>
    public bool IsNativeEngine
    {
        get => _settings.Current.Engine == EngineKind.Native;
        set
        {
            var kind = value ? EngineKind.Native : EngineKind.GoodbyeDpi;
            if (_settings.Current.Engine == kind) return;

            _settings.Current.Engine = kind;
            _settings.Save();

            OnPropertyChanged();
            OnPropertyChanged(nameof(IsGoodbyeEngine));
            OnPropertyChanged(nameof(ShowNativeSettings));
            OnPropertyChanged(nameof(ShowCustomNative));
            OnPropertyChanged(nameof(ProfileSummary));
            RefreshConnectedDetail();
            RestartIfRunning();
        }
    }

    public bool IsGoodbyeEngine
    {
        get => !IsNativeEngine;
        set => IsNativeEngine = !value;
    }

    public bool ShowNativeSettings => IsNativeEngine;

    // ------------------------------------------------------------ yontem / profil

    public IReadOnlyList<DpiMethod> Methods => DpiMethod.All;
    public IReadOnlyList<IspProfile> Isps => IspProfile.All;

    /// <summary>Acilir listedeki yontemler: saglayicinin onerileri + kendi profillerin.</summary>
    public IReadOnlyList<NativeProfile> NativeProfiles => _nativeProfiles;

    /// <summary>Yerlesik sunucular + kendi DNS girislerin.</summary>
    public IReadOnlyList<DnsProfile> DnsProfiles => _dnsProfiles;

    private void RebuildNativeProfiles() =>
        _nativeProfiles = [.. SelectedIsp.Methods, .. _settings.Current.CustomProfiles.Select(p => p.ToProfile())];

    private void RebuildDnsProfiles() =>
        _dnsProfiles = [.. DnsProfile.BuiltIn, .. _settings.Current.CustomDns.Select(e => e.ToProfile())];

    public IspProfile SelectedIsp
    {
        get => IspProfile.FromId(_settings.Current.Isp);
        set
        {
            if (value is null || value.Id == SelectedIsp.Id) return;

            // Saglayici secilince onun onerdigi yontem, GoodbyeDPI yontemi ve DNS birlikte
            // uygulanir; kullanici sonra istedigini degistirebilir. Kendi girdigi ozel DNS'e
            // dokunulmaz. Tek seferde kaydedip bir kez yeniden baslatiyoruz.
            _settings.Current.Isp = value.Id;
            _settings.Current.NativeProfile = value.Recommended.Id;
            _settings.Current.Method = value.GoodbyeMethodId;
            if (value.DnsId is not null && !DnsProfile.IsCustomId(_settings.Current.Dns))
                _settings.Current.Dns = value.DnsId;
            _settings.Save();

            RebuildNativeProfiles();

            OnPropertyChanged();
            OnPropertyChanged(nameof(SelectedMethod));
            RaiseNativeList();
            RaiseDnsSelection();
            RefreshConnectedDetail();
            RestartIfRunning();
        }
    }

    public DpiMethod SelectedMethod
    {
        get => DpiMethod.FromId(_settings.Current.Method);
        set
        {
            if (value is null || value.Id == _settings.Current.Method) return;
            _settings.Current.Method = value.Id;
            _settings.Save();
            OnPropertyChanged();
            OnPropertyChanged(nameof(ProfileSummary));
            RefreshConnectedDetail();
            RestartIfRunning();
        }
    }

    public NativeProfile SelectedNativeProfile
    {
        get
        {
            // Listede olmayan kayitli kimlik (elle duzenlenmis dosya / silinmis profil):
            // saglayicinin onerisine dus ki secili oge ile motorun kullandigi ayni olsun.
            var id = _settings.Current.NativeProfile;
            return _nativeProfiles.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase))
                   ?? SelectedIsp.Recommended;
        }
        set
        {
            if (value is null) return;
            if (string.Equals(value.Id, _settings.Current.NativeProfile, StringComparison.OrdinalIgnoreCase)) return;

            _settings.Current.NativeProfile = value.Id;
            _settings.Save();
            RaiseNativeSelection();
            RefreshConnectedDetail();
            RestartIfRunning();
        }
    }

    public DnsProfile SelectedDns
    {
        get
        {
            var id = _settings.Current.Dns;
            return _dnsProfiles.FirstOrDefault(p => string.Equals(p.Id, id, StringComparison.OrdinalIgnoreCase))
                   ?? DnsProfile.Cloudflare;
        }
        set
        {
            if (value is null) return;
            if (string.Equals(value.Id, _settings.Current.Dns, StringComparison.OrdinalIgnoreCase)) return;

            _settings.Current.Dns = value.Id;
            _settings.Save();
            DnsWarning = SelectedDns.Validate() ?? string.Empty;
            RaiseDnsSelection();
            RefreshConnectedDetail();
            if (!HasDnsWarning) RestartIfRunning();
        }
    }

    /// <summary>Su an duzenlenen ozel yontem profili; yerlesik bir yontem seciliyse null.</summary>
    private CustomNativeProfile? CurrentCustom =>
        _settings.Current.CustomProfiles.FirstOrDefault(p =>
            string.Equals(p.Id, _settings.Current.NativeProfile, StringComparison.OrdinalIgnoreCase));

    /// <summary>Su an duzenlenen ozel DNS girisi; yerlesik bir sunucu seciliyse null.</summary>
    private CustomDnsEntry? CurrentCustomDns =>
        _settings.Current.CustomDns.FirstOrDefault(e =>
            string.Equals(e.Id, _settings.Current.Dns, StringComparison.OrdinalIgnoreCase));

    public bool ShowCustomNative => IsNativeEngine && CurrentCustom is not null;

    public bool ShowCustomDns => CurrentCustomDns is not null;

    // ------------------------------------------------------------ ozel profil yonetimi

    public RelayCommand AddCustomProfileCommand { get; }
    public RelayCommand DeleteCustomProfileCommand { get; }
    public RelayCommand AddCustomDnsCommand { get; }
    public RelayCommand DeleteCustomDnsCommand { get; }

    /// <summary>Duzenlenen ozel profilin adi; listede bu adla gorunur.</summary>
    public string CustomProfileName
    {
        get => CurrentCustom?.Name ?? string.Empty;
        set
        {
            if (CurrentCustom is not { } entry) return;

            var name = CustomIds.CleanName(value, CustomNativeProfile.DefaultName);
            if (name == entry.Name) return;

            entry.Name = name;
            _settings.Save();
            RebuildNativeProfiles();
            Post(RaiseNativeList);
        }
    }

    public string CustomDnsName
    {
        get => CurrentCustomDns?.Name ?? string.Empty;
        set
        {
            if (CurrentCustomDns is not { } entry) return;

            var name = CustomIds.CleanName(value, CustomDnsEntry.DefaultName);
            if (name == entry.Name) return;

            entry.Name = name;
            _settings.Save();
            RebuildDnsProfiles();
            Post(RaiseDnsList);
        }
    }

    /// <summary>Secili yontemi kopyalayarak yeni bir ozel profil olusturur ve ona gecer.</summary>
    private void AddCustomProfile()
    {
        var source = SelectedNativeProfile;

        // Yerlesik bir yontemden turetiliyorsa adi karismasin diye isaretlenir;
        // zaten ozel bir profildeysek islem "cogalt" anlamina gelir.
        var name = NativeProfile.IsCustomId(source.Id) ? source.Name : $"{source.Name} (özel)";

        var list = _settings.Current.CustomProfiles;
        var entry = CustomNativeProfile.CreateNew(list, source.Build(), name);
        list.Add(entry);

        _settings.Current.NativeProfile = entry.Id;
        _settings.Save();

        RebuildNativeProfiles();
        RaiseNativeList();
        RefreshConnectedDetail();
        RestartIfRunning();
    }

    private void DeleteCustomProfile()
    {
        if (CurrentCustom is not { } entry) return;

        _settings.Current.CustomProfiles.Remove(entry);
        // Silinen profil seciliydi: saglayicinin onerdigi yonteme don.
        _settings.Current.NativeProfile = SelectedIsp.Recommended.Id;
        _settings.Save();

        RebuildNativeProfiles();
        RaiseNativeList();
        RefreshConnectedDetail();
        RestartIfRunning();
    }

    private void AddCustomDns()
    {
        var list = _settings.Current.CustomDns;
        var entry = CustomDnsEntry.CreateNew(list, CurrentCustomDns);
        list.Add(entry);

        _settings.Current.Dns = entry.Id;
        _settings.Save();

        RebuildDnsProfiles();
        DnsWarning = string.Empty;
        RaiseDnsList();
        RefreshConnectedDetail();
        RestartIfRunning();
    }

    private void DeleteCustomDns()
    {
        if (CurrentCustomDns is not { } entry) return;

        _settings.Current.CustomDns.Remove(entry);
        // Silinen giris seciliydi: saglayicinin onerdigi DNS'e, o da yoksa Cloudflare'e don.
        _settings.Current.Dns = SelectedIsp.DnsId ?? DnsProfile.Cloudflare.Id;
        _settings.Save();

        RebuildDnsProfiles();
        DnsWarning = string.Empty;
        RaiseDnsList();
        RefreshConnectedDetail();
        RestartIfRunning();
    }

    /// <summary>Yalnizca secim degisti; listedeki ogeler ayni kaldi.</summary>
    private void RaiseNativeSelection()
    {
        OnPropertyChanged(nameof(SelectedNativeProfile));
        OnPropertyChanged(nameof(ShowCustomNative));
        OnPropertyChanged(nameof(CustomProfileName));
        OnPropertyChanged(nameof(ProfileSummary));
        RaiseNativeOptions();
    }

    /// <summary>Yalnizca secim degisti; listedeki girisler ayni kaldi.</summary>
    private void RaiseDnsSelection()
    {
        OnPropertyChanged(nameof(SelectedDns));
        OnPropertyChanged(nameof(ShowCustomDns));
        OnPropertyChanged(nameof(CustomDnsName));
        OnPropertyChanged(nameof(DnsCustomV4));
        OnPropertyChanged(nameof(DnsCustomV4Port));
        OnPropertyChanged(nameof(DnsCustomV6));
        OnPropertyChanged(nameof(DnsCustomV6Port));
        OnPropertyChanged(nameof(ProfileSummary));
    }

    /// <summary>Liste degisti: oge eklendi/silindi ya da bir ogenin adi/ozeti degisti.</summary>
    private void RaiseNativeList()
    {
        Requery(ref _nativeProfiles, nameof(NativeProfiles));
        RaiseNativeSelection();
    }

    private void RaiseDnsList()
    {
        Requery(ref _dnsProfiles, nameof(DnsProfiles));
        RaiseDnsSelection();
    }

    /// <summary>
    /// Liste kaynagini once bosaltip hemen geri verir.
    ///
    /// YALNIZCA liste ogeleri degistiginde cagrilmali ve hicbir zaman ayni kutunun
    /// kendi secim yaziminin ortasinda: ComboBox kaynagini guncellerken ItemsSource'unu
    /// degistirmek ic ice guncellemeye yol acar.
    ///
    /// ComboBox yeni listede ESIT bir oge bulunca secili NESNEYI degistirmiyor; kimlik
    /// bazli esitlikle bu, adi ya da ozeti degisen profilin kutuda eski haliyle kalmasi
    /// demekti. Bosaltma secimi dusuruyor, hemen ardindan gelen bildirim guncel nesneyi
    /// sectiriyor. Iki adim ayni gonderici turunda oldugu icin arada kare cizilmiyor.
    /// </summary>
    private void Requery<T>(ref IReadOnlyList<T> list, string name)
    {
        var current = list;

        list = [];
        OnPropertyChanged(name);

        list = current;
        OnPropertyChanged(name);
    }

    /// <summary>
    /// Isi bir sonraki gonderici turuna birakir. ComboBox / TextBox kaynagi yazarken
    /// ayni ozelligi geri bildirmek WPF tarafindan yok sayiliyor; bekleyip bildirince
    /// arayuz gercekten tazeleniyor.
    /// </summary>
    private static void Post(Action action)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is null) action();
        else dispatcher.BeginInvoke(action);
    }

    // ------------------------------------------------------------ ozel DNS girisi

    public string DnsCustomV4
    {
        get => CurrentCustomDns?.V4 ?? string.Empty;
        set => SetDnsField(e => e.V4 = value?.Trim() ?? string.Empty);
    }

    public string DnsCustomV4Port
    {
        get => (CurrentCustomDns?.V4Port ?? 53).ToString(CultureInfo.CurrentCulture);
        set => SetDnsPort((e, port) => e.V4Port = port, value);
    }

    public string DnsCustomV6
    {
        get => CurrentCustomDns?.V6 ?? string.Empty;
        set => SetDnsField(e => e.V6 = value?.Trim() ?? string.Empty);
    }

    public string DnsCustomV6Port
    {
        get => (CurrentCustomDns?.V6Port ?? 53).ToString(CultureInfo.CurrentCulture);
        set => SetDnsPort((e, port) => e.V6Port = port, value);
    }

    private string _dnsWarning = string.Empty;
    public string DnsWarning
    {
        get => _dnsWarning;
        private set
        {
            if (SetField(ref _dnsWarning, value)) OnPropertyChanged(nameof(HasDnsWarning));
        }
    }
    public bool HasDnsWarning => !string.IsNullOrEmpty(DnsWarning);

    private void SetDnsField(Action<CustomDnsEntry> apply)
    {
        if (CurrentCustomDns is not { } entry) return;
        apply(entry);
        _settings.Save();
        OnCustomDnsChanged();
    }

    private void SetDnsPort(Action<CustomDnsEntry, int> apply, string? text)
    {
        if (CurrentCustomDns is not { } entry) return;
        if (!int.TryParse(text, out var port)) { DnsWarning = "Port bir sayı olmalı."; return; }
        if (port is < 0 or > 65535) { DnsWarning = "Port 0-65535 aralığında olmalı."; return; }

        apply(entry, port);
        _settings.Save();
        OnCustomDnsChanged();
    }

    /// <summary>Ozel DNS alanlari degistiginde listeyi yenile, dogrula, gerekirse baglantiyi tazele.</summary>
    private void OnCustomDnsChanged()
    {
        RebuildDnsProfiles();
        DnsWarning = SelectedDns.Validate() ?? string.Empty;
        Post(RaiseDnsList);

        if (!HasDnsWarning) RestartIfRunning();
    }

    // ------------------------------------------------------------ ozel native ayarlari

    private NativeDpiConfig Cfg => CurrentCustom?.Config ?? _noProfile;

    public bool NativeFakePacket
    {
        get => Cfg.FakePacket;
        set => SetNative(() => Cfg.FakePacket = value, Cfg.FakePacket != value);
    }

    public bool NativeFakeTtl
    {
        get => Cfg.FakeTtl;
        set => SetNative(() => Cfg.FakeTtl = value, Cfg.FakeTtl != value);
    }

    public bool NativeAutoTtl
    {
        get => Cfg.AutoTtl;
        set => SetNative(() => Cfg.AutoTtl = value, Cfg.AutoTtl != value);
    }

    public int NativeTtl
    {
        get => Cfg.Ttl;
        set
        {
            var clamped = Math.Clamp(value, 1, 255);
            SetNative(() => Cfg.Ttl = clamped, Cfg.Ttl != clamped);
            if (clamped != value) OnPropertyChanged(); // kutudaki gecersiz degeri duzelt
        }
    }

    public bool NativeWrongChecksum
    {
        get => Cfg.FakeWrongChecksum;
        set => SetNative(() => Cfg.FakeWrongChecksum = value, Cfg.FakeWrongChecksum != value);
    }

    public bool NativeWrongSeq
    {
        get => Cfg.FakeWrongSeq;
        set => SetNative(() => Cfg.FakeWrongSeq = value, Cfg.FakeWrongSeq != value);
    }

    public bool NativeSplitTls
    {
        get => Cfg.SplitTls;
        set => SetNative(() => Cfg.SplitTls = value, Cfg.SplitTls != value);
    }

    public int NativeSplitPosition
    {
        get => Cfg.SplitPosition;
        set
        {
            var clamped = Math.Clamp(value, 0, 1400);
            SetNative(() => Cfg.SplitPosition = clamped, Cfg.SplitPosition != clamped);
            if (clamped != value) OnPropertyChanged();
        }
    }

    public bool NativeSplitSni
    {
        get => Cfg.SplitSni;
        set => SetNative(() => Cfg.SplitSni = value, Cfg.SplitSni != value);
    }

    public bool NativeReverseSplit
    {
        get => Cfg.ReverseSplit;
        set => SetNative(() => Cfg.ReverseSplit = value, Cfg.ReverseSplit != value);
    }

    public bool NativeBlockQuic
    {
        get => Cfg.BlockQuic;
        set => SetNative(() => Cfg.BlockQuic = value, Cfg.BlockQuic != value);
    }

    public bool NativeFragmentHttp
    {
        get => Cfg.FragmentHttp;
        set => SetNative(() => Cfg.FragmentHttp = value, Cfg.FragmentHttp != value);
    }

    public bool NativeMd5Sig
    {
        get => Cfg.FakeMd5Sig;
        set => SetNative(() => Cfg.FakeMd5Sig = value, Cfg.FakeMd5Sig != value);
    }

    public bool NativeZeroFake
    {
        get => Cfg.FakePayload == FakePayloadKind.Zeros;
        set
        {
            var kind = value ? FakePayloadKind.Zeros : FakePayloadKind.Tls;
            SetNative(() => Cfg.FakePayload = kind, Cfg.FakePayload != kind);
        }
    }

    public bool NativeSplitFake
    {
        get => Cfg.SplitFake;
        set => SetNative(() => Cfg.SplitFake = value, Cfg.SplitFake != value);
    }

    public int NativeFakeRepeats
    {
        get => Cfg.FakeRepeats;
        set
        {
            var clamped = Math.Clamp(value, 1, 10);
            SetNative(() => Cfg.FakeRepeats = clamped, Cfg.FakeRepeats != clamped);
            if (clamped != value) OnPropertyChanged();
        }
    }

    public int NativeSeqOverlap
    {
        get => Cfg.SeqOverlap;
        set
        {
            var clamped = Math.Clamp(value, 0, 1000);
            SetNative(() => Cfg.SeqOverlap = clamped, Cfg.SeqOverlap != clamped);
            if (clamped != value) OnPropertyChanged();
        }
    }

    public bool NativeVoiceFake
    {
        get => Cfg.VoiceFake;
        set => SetNative(() => Cfg.VoiceFake = value, Cfg.VoiceFake != value);
    }

    // Bagimli satirlar: ust ayar kapaliyken alt ayarlar soluk ve pasif gorunur.

    /// <summary>TTL satirlari yalnizca sahte paket + dusuk TTL acikken anlamli.</summary>
    public bool IsNativeTtlEditable => Cfg.FakePacket && Cfg.FakeTtl;

    /// <summary>Sabit TTL degeri otomatik TTL kapaliyken asil deger, aciksa yedek.</summary>
    public string NativeTtlHint => Cfg.AutoTtl
        ? "Otomatik TTL ölçülemezse kullanılır"
        : "Sahte paket bu kadar yönlendirici sonra düşer";

    /// <summary>Sahte paket acik ama onu koruyan hicbir yontem secili degil.</summary>
    public bool ShowFakeProtectionHint => Cfg.FakePacket && !Cfg.HasFakeProtection;

    /// <summary>Hicbir atlatma teknigi secili degil: motor yalnizca DNS/QUIC isler.</summary>
    public bool ShowNoTechniqueHint => !Cfg.FakePacket && !Cfg.SplitTls;

    public RelayCommand ResetCustomNativeCommand { get; }

    private static readonly string[] NativeOptionProperties =
    [
        nameof(NativeFakePacket), nameof(NativeFakeTtl), nameof(NativeAutoTtl), nameof(NativeTtl),
        nameof(NativeWrongChecksum), nameof(NativeWrongSeq), nameof(NativeSplitTls),
        nameof(NativeSplitPosition), nameof(NativeSplitSni), nameof(NativeReverseSplit),
        nameof(NativeBlockQuic), nameof(NativeFragmentHttp),
        nameof(NativeMd5Sig), nameof(NativeZeroFake), nameof(NativeSplitFake), nameof(NativeFakeRepeats),
        nameof(NativeSeqOverlap), nameof(NativeVoiceFake),
        nameof(IsNativeTtlEditable), nameof(NativeTtlHint),
        nameof(ShowFakeProtectionHint), nameof(ShowNoTechniqueHint),
    ];

    private void SetNative(Action apply, bool changed)
    {
        // Yerlesik bir yontem seciliyken ayar satirlari gorunmez; yine de gelen
        // bir yazma istegi bos yapilandirmayi kirletmesin.
        if (!changed || CurrentCustom is null) return;

        apply();
        _settings.Save();

        // Listedeki ozet metni ("sahte paket (oto TTL) · bölme ...") degisti.
        RebuildNativeProfiles();
        RaiseNativeList();
        RestartIfRunning();
    }

    private void RaiseNativeOptions()
    {
        foreach (var name in NativeOptionProperties) OnPropertyChanged(name);
    }

    /// <summary>Duzenlenen profili secili saglayicinin onerdigi yontemin ayarlarina dondurur.</summary>
    private void ResetCustomNative()
    {
        if (CurrentCustom is not { } entry) return;

        entry.Config = SelectedIsp.Recommended.Build();
        _settings.Save();

        RebuildNativeProfiles();
        RaiseNativeList();
        RestartIfRunning();
    }

    // ------------------------------------------------------------ ozet / uyari

    public string StartupWarning
    {
        get => _startupWarning;
        private set
        {
            if (SetField(ref _startupWarning, value)) OnPropertyChanged(nameof(HasStartupWarning));
        }
    }

    public bool HasStartupWarning => !string.IsNullOrEmpty(StartupWarning);

    public string VersionText => $"GoodbyeDPI UI {UpdateService.CurrentVersion.ToString(3)}";

    /// <summary>Alt satirda gosterilen ozet: "Motor · Saglayici · Yontem · DNS".</summary>
    public string ProfileSummary
    {
        get
        {
            var engine = IsNativeEngine ? "Kendi motoru" : "GoodbyeDPI";
            var method = IsNativeEngine ? SelectedNativeProfile.Name : SelectedMethod.Name;
            var isp = SelectedIsp.Id == IspProfile.GeneralId ? "" : SelectedIsp.Name + " · ";
            return $"{engine} · {isp}{method} · DNS: {SelectedDns.Name}";
        }
    }

    public void ReportStartupProblem(string? error)
    {
        StartupWarning = string.IsNullOrWhiteSpace(error)
            ? "Windows ile başlatma ayarlanamadı."
            : "Windows ile başlatma ayarlanamadı: " + error;
    }

    // ----------------------------------------------------------- komutlar

    public RelayCommand ToggleConnectionCommand { get; }

    public async Task ToggleAsync()
    {
        if (IsBusy) return;

        if (State is ConnectionState.Connected or ConnectionState.Connecting)
        {
            _dpi.Stop();
            return;
        }

        await ConnectAsync();
    }

    public async Task ConnectAsync()
    {
        if (IsBusy) return;

        IsBusy = true;
        try
        {
            await _dpi.StartAsync(_settings.Current.Engine, BuildRequest());
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>Secili ayarlardan motorun ihtiyac duydugu istegi olusturur.</summary>
    private EngineRequest BuildRequest() =>
        // Ozel profillerin Build'i ve DNS girisleri listede zaten canli tutuluyor.
        new(SelectedMethod, SelectedNativeProfile.Build(), SelectedDns);

    public async Task AutoConnectAsync()
    {
        int[] retryDelaysSeconds = [3, 8, 15];

        await ConnectAsync();

        foreach (var delay in retryDelaysSeconds)
        {
            if (State != ConnectionState.Failed) return;
            await Task.Delay(TimeSpan.FromSeconds(delay));
            await ConnectAsync();
        }
    }

    private async void RestartIfRunning()
    {
        if (State is ConnectionState.Connected or ConnectionState.Connecting)
            await ConnectAsync();
    }

    // ----------------------------------------------------------- guncelleme

    public RelayCommand ApplyUpdateCommand { get; }
    public RelayCommand LaterUpdateCommand { get; }
    public RelayCommand DismissUpdateDoneCommand { get; }

    /// <summary>Guncelleme karti gorunur mu (indirme / dogrulama / kurulum / hata).</summary>
    public bool ShowUpdateScreen => _updateScreenVisible;

    /// <summary>Kart kapatildiktan sonra govdede duran kucuk "yeni surum" seridi.</summary>
    public bool UpdateAvailable => _updateDeferred;

    public string UpdateBannerText => _updateInfo is { } info
        ? _pendingSetup is null
            ? $"Yeni sürüm var: {info.Version.ToString(3)}"
            : $"{info.Version.ToString(3)} indirildi, kurulmayı bekliyor."
        : string.Empty;

    /// <summary>Guncelleme sonrasi ilk acilista gosterilen "guncellendi" bildirimi.</summary>
    public string UpdateDoneText
    {
        get => _updateDoneText;
        private set
        {
            if (SetField(ref _updateDoneText, value)) OnPropertyChanged(nameof(HasUpdateDone));
        }
    }

    public bool HasUpdateDone => !string.IsNullOrEmpty(UpdateDoneText);

    public string UpdateTitle => _updateStage switch
    {
        UpdateStage.Checking => "Güncelleme aranıyor",
        UpdateStage.Downloading => "Yeni sürüm indiriliyor",
        UpdateStage.Verifying => "Dosya doğrulanıyor",
        UpdateStage.Ready => "Güncelleme hazır",
        UpdateStage.Installing => "Güncelleme kuruluyor",
        UpdateStage.Failed => "Güncelleme tamamlanamadı",
        _ => "Güncelleme",
    };

    public string UpdateMessage => _updateStage switch
    {
        UpdateStage.Checking => "GitHub'daki en son sürüme bakılıyor.",
        UpdateStage.Downloading =>
            "İndirme bitince uygulama birkaç saniyeliğine kapanacak ve kendini yeniden açacak. Bu sırada koruma kapalı kalır.",
        UpdateStage.Verifying => "İnen dosyanın GitHub'daki sürümle birebir aynı olduğu kontrol ediliyor.",
        UpdateStage.Ready => "Kurulum birkaç saniye sürer ve uygulama kendini yeniden açar.",
        UpdateStage.Installing => "Uygulama şimdi kapanıyor. Kurulum bitince kendini yeniden açacak, bir şey yapmana gerek yok.",
        UpdateStage.Failed => _updateError,
        _ => string.Empty,
    };

    /// <summary>Kartin ustundeki daire icinde gosterilen simge (Segoe Fluent Icons).</summary>
    public string UpdateGlyph => _updateStage switch
    {
        UpdateStage.Downloading => "", // indir
        UpdateStage.Verifying => "",   // kilit / dogrulama
        UpdateStage.Ready => "",       // tamamlandi
        UpdateStage.Installing => "",  // esitleniyor
        UpdateStage.Failed => "",      // uyari
        _ => "",
    };

    /// <summary>Simgenin cevresindeki yayin donup donmeyecegi.</summary>
    public bool UpdateBusy => _updateStage is UpdateStage.Checking or UpdateStage.Downloading
        or UpdateStage.Verifying or UpdateStage.Installing;

    public bool ShowUpdateProgress => _updateStage is UpdateStage.Downloading;

    public bool ShowUpdateRetry => _updateStage is UpdateStage.Failed;

    /// <summary>"Daha sonra" dugmesi: kurulum baslamissa geri donus yok, gizlenir.</summary>
    public bool ShowUpdateLater => _updateStage is not UpdateStage.Installing;

    public string UpdateVersionText => _updateInfo is { } info
        ? $"{UpdateService.CurrentVersion.ToString(3)}  →  {info.Version.ToString(3)}"
        : UpdateService.CurrentVersion.ToString(3);

    /// <summary>Ilerleme cubugunun dolulugu (0-1).</summary>
    public double UpdateFraction
    {
        get => _updateFraction;
        private set => SetField(ref _updateFraction, value);
    }

    public string UpdateProgressText
    {
        get => _updateProgressText;
        private set => SetField(ref _updateProgressText, value);
    }

    /// <summary>
    /// Acilista cagrilir: GitHub'da yeni surum varsa indirir ve kurar. AutoUpdate
    /// kapaliysa hicbir sey yapilmaz (ag istegi de gonderilmez).
    /// </summary>
    public async Task CheckForUpdatesAsync()
    {
        if (!AutoUpdate || _updateStage != UpdateStage.None) return;

        SetStage(UpdateStage.Checking); // ekran acilmaz: sessiz arama

        var info = await _updates.CheckAsync();
        if (info is null)
        {
            SetStage(UpdateStage.None);
            return;
        }

        _updateInfo = info;
        await StartUpdateAsync();
    }

    /// <summary>
    /// Indirmeyi baslatir (gerekirse once surumu arar); dosya zaten inmisse dogrudan kurar.
    /// Hem acilistaki otomatik akis hem de "Şimdi güncelle" dugmesi buraya gelir.
    /// </summary>
    private async Task StartUpdateAsync()
    {
        if (_updateStage is UpdateStage.Downloading or UpdateStage.Verifying or UpdateStage.Installing) return;

        if (_updateInfo is null)
        {
            SetStage(UpdateStage.Checking);
            ShowScreen(true);

            _updateInfo = await _updates.CheckAsync();
            if (_updateInfo is null)
            {
                Fail("Yeni sürüm bulunamadı ya da GitHub'a ulaşılamadı.");
                return;
            }
        }

        var info = _updateInfo;

        // Daha once indirilip kurulmadan birakilmissa tekrar indirme.
        if (_pendingSetup is not null && File.Exists(_pendingSetup))
        {
            _ = InstallAsync();
            return;
        }

        _pendingSetup = null;
        SetDeferred(false);

        SetStage(UpdateStage.Downloading);
        SetProgress(0, info.Size);
        ShowScreen(true);

        _updateCancel?.Dispose();
        _updateCancel = new CancellationTokenSource();
        var token = _updateCancel.Token;

        var progress = new Progress<DownloadProgress>(p =>
        {
            SetProgress(p.Done, p.Total);

            // Son bayt da indi: kalan sure SHA-256 hesabi.
            if (p.Total > 0 && p.Done >= p.Total && _updateStage == UpdateStage.Downloading)
                SetStage(UpdateStage.Verifying);
        });

        var path = await _updates.DownloadAsync(info, progress, token);

        if (token.IsCancellationRequested) return; // "Daha sonra" secildi

        if (path is null)
        {
            Fail("İndirme tamamlanamadı. İnternet bağlantını kontrol edip tekrar deneyebilirsin.");
            return;
        }

        _pendingSetup = path;
        _ = InstallAsync();
    }

    /// <summary>Kurucuyu baslatip uygulamadan cikar; kurucu yeni surumu acar.</summary>
    private async Task InstallAsync()
    {
        if (_pendingSetup is null) return;

        SetStage(UpdateStage.Installing);
        ShowScreen(true);

        // Kullanici ne oldugunu okuyabilsin diye kisa bir an bekleniyor.
        await Task.Delay(1200);

        // Yeniden acilista "guncellendi" diyebilmek icin isaret birakiliyor.
        _settings.Current.PendingUpdate = _updateInfo?.Version.ToString(3);
        _settings.Save();

        if (!UpdateService.LaunchInstaller(_pendingSetup, RelaunchArguments))
        {
            _settings.Current.PendingUpdate = null;
            _settings.Save();
            Fail("Kurulum başlatılamadı.");
            return;
        }

        // Baglantiyi birak, kurucu dosyalari degistirebilsin.
        _dpi.Stop();
        RequestShutdown?.Invoke();
    }

    /// <summary>"Daha sonra": indirmeyi iptal eder, karti kapatir, seridi birakir.</summary>
    private void DeferUpdate()
    {
        _updateCancel?.Cancel();

        ShowScreen(false);
        SetStage(_pendingSetup is null ? UpdateStage.None : UpdateStage.Ready);
        SetDeferred(_updateInfo is not null);
    }

    private void SetDeferred(bool value)
    {
        _updateDeferred = value;
        OnPropertyChanged(nameof(UpdateAvailable));
        OnPropertyChanged(nameof(UpdateBannerText));
    }

    private void Fail(string message)
    {
        _updateError = message;
        SetStage(UpdateStage.Failed);
        ShowScreen(true);
    }

    private void ShowScreen(bool value)
    {
        if (_updateScreenVisible == value) return;
        _updateScreenVisible = value;
        OnPropertyChanged(nameof(ShowUpdateScreen));
    }

    private void SetStage(UpdateStage stage)
    {
        if (_updateStage == stage) return;
        _updateStage = stage;

        OnPropertyChanged(nameof(UpdateTitle));
        OnPropertyChanged(nameof(UpdateMessage));
        OnPropertyChanged(nameof(UpdateGlyph));
        OnPropertyChanged(nameof(UpdateBusy));
        OnPropertyChanged(nameof(UpdateVersionText));
        OnPropertyChanged(nameof(ShowUpdateProgress));
        OnPropertyChanged(nameof(ShowUpdateRetry));
        OnPropertyChanged(nameof(ShowUpdateLater));
    }

    private void SetProgress(long done, long total)
    {
        UpdateFraction = total > 0 ? Math.Clamp((double)done / total, 0, 1) : 0;

        UpdateProgressText = total > 0
            ? $"{Megabytes(done)} / {Megabytes(total)} MB  ·  %{UpdateFraction * 100:0}"
            : $"{Megabytes(done)} MB";
    }

    private static string Megabytes(long bytes) =>
        (bytes / 1048576.0).ToString("0.0", CultureInfo.CurrentCulture);

#if UITEST
    /// <summary>
    /// Yalnizca gorsel dogrulama derlemesi: guncelleme ekranini gercek bir indirme
    /// yapmadan istenen adimda gosterir.
    /// </summary>
    public void PreviewUpdate(UpdateStage stage, long done, long total)
    {
        _updateInfo = new UpdateInfo(new Version(2, 4, 0), "v2.4.0", "GoodbyeDPI-UI-Setup.exe", "", null, total);
        _updateError = "İndirme tamamlanamadı. İnternet bağlantını kontrol edip tekrar deneyebilirsin.";

        SetStage(stage);
        SetProgress(done, total);
        ShowScreen(stage != UpdateStage.None);
        OnPropertyChanged(nameof(UpdateVersionText));
    }

    /// <summary>Yalnizca dogrulama derlemesi: "guncellendi" seridini gosterir.</summary>
    public void PreviewUpdateDone() =>
        UpdateDoneText = $"{UpdateService.CurrentVersion.ToString(3)} sürümüne güncellendi.";
#endif

    /// <summary>
    /// Kurulumdan sonraki ilk acilis: birakilan isaret calisan surumle esitse
    /// kullaniciya guncellendigi soylenir. Isaret her durumda temizlenir.
    /// </summary>
    private void ReportFinishedUpdate()
    {
        if (_settings.Current.PendingUpdate is not { Length: > 0 } pending) return;

        _settings.Current.PendingUpdate = null;
        _settings.Save();

        if (Version.TryParse(pending, out var target) && target <= UpdateService.CurrentVersion)
            UpdateDoneText = $"{UpdateService.CurrentVersion.ToString(3)} sürümüne güncellendi.";
    }

    // ---------------------------------------------------------- bildirim

    private void OnStateChanged(object? sender, EventArgs e)
    {
        var dispatcher = Application.Current?.Dispatcher;
        if (dispatcher is not null && !dispatcher.CheckAccess())
        {
            dispatcher.BeginInvoke(UpdateStatus);
            return;
        }

        UpdateStatus();
    }

    /// <summary>Bagliyken durum altindaki ozet satirini secimlere gore tazeler.</summary>
    private void RefreshConnectedDetail()
    {
        if (State == ConnectionState.Connected) StatusDetail = ProfileSummary;
    }

    private void UpdateStatus()
    {
        StatusText = State switch
        {
            ConnectionState.Connected => "Bağlandı",
            ConnectionState.Connecting => "Bağlanıyor",
            ConnectionState.Failed => "Hata",
            _ => "Kapalı",
        };

        StatusDetail = State switch
        {
            ConnectionState.Failed => _dpi.LastError ?? "Bilinmeyen hata.",
            ConnectionState.Connected => ProfileSummary,
            _ => string.Empty,
        };

        OnPropertyChanged(nameof(State));
        OnPropertyChanged(nameof(IsIdle));
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(IsConnecting));
        OnPropertyChanged(nameof(IsFailed));
    }
}

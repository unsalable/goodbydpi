using System.Windows;
using GoodbyeDpiUI.Models;
using GoodbyeDpiUI.Services;

namespace GoodbyeDpiUI.ViewModels;

public sealed class MainViewModel : ObservableObject
{
    private readonly DpiController _dpi;
    private readonly SettingsService _settings;
    private readonly ThemeService _theme;
    private readonly UpdateService _updates;

    private string _statusText = "Kapali";
    private string _statusDetail = string.Empty;
    private bool _isBusy;
    private bool _areAnimationsEnabled = true;
    private string _startupWarning = string.Empty;

    private IReadOnlyList<DnsProfile> _dnsProfiles = Array.Empty<DnsProfile>();

    // Guncelleme durumu
    private bool _updateAvailable;
    private bool _updateReady;
    private string _updateStatus = string.Empty;
    private string? _pendingSetup;

    /// <summary>App tarafindan atanir: guncelleme kuruluma gecerken uygulamadan cikar.</summary>
    public Action? RequestShutdown { get; set; }

    public MainViewModel(DpiController dpi, SettingsService settings, ThemeService theme, UpdateService updates)
    {
        _dpi = dpi;
        _settings = settings;
        _theme = theme;
        _updates = updates;

        _dpi.StateChanged += OnStateChanged;

        RebuildDnsProfiles();

        ToggleConnectionCommand = new RelayCommand(() => _ = ToggleAsync(), () => !IsBusy);
        ApplyUpdateCommand = new RelayCommand(ApplyUpdate, () => _updateReady);

        UpdateStatus();
    }

    // ------------------------------------------------------------- durum

    public ConnectionState State => _dpi.State;
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
            OnPropertyChanged(nameof(ProfileSummary));
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
    public IReadOnlyList<NativeProfile> NativeProfiles => NativeProfile.All;
    public IReadOnlyList<DnsProfile> DnsProfiles => _dnsProfiles;

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
            RestartIfRunning();
        }
    }

    public NativeProfile SelectedNativeProfile
    {
        get => NativeProfile.FromId(_settings.Current.NativeProfile);
        set
        {
            if (value is null || value.Id == _settings.Current.NativeProfile) return;
            _settings.Current.NativeProfile = value.Id;
            _settings.Save();
            OnPropertyChanged();
            OnPropertyChanged(nameof(ShowCustomNative));
            OnPropertyChanged(nameof(ProfileSummary));
            RestartIfRunning();
        }
    }

    public DnsProfile SelectedDns
    {
        get
        {
            var id = _settings.Current.Dns;
            return _dnsProfiles.FirstOrDefault(p => p.Id == id) ?? DnsProfile.Cloudflare;
        }
        set
        {
            if (value is null || value.Id == _settings.Current.Dns) return;
            _settings.Current.Dns = value.Id;
            _settings.Save();
            OnPropertyChanged();
            OnPropertyChanged(nameof(ShowCustomDns));
            OnPropertyChanged(nameof(ProfileSummary));
            RestartIfRunning();
        }
    }

    public bool ShowCustomNative => IsNativeEngine && SelectedNativeProfile.Id == NativeProfile.CustomId;

    public bool ShowCustomDns => SelectedDns.Id == DnsProfile.CustomId;

    // ------------------------------------------------------------ ozel DNS girisi

    public string DnsCustomV4
    {
        get => _settings.Current.DnsCustomV4;
        set => SetDnsField(v => _settings.Current.DnsCustomV4 = v, value?.Trim() ?? string.Empty, _settings.Current.DnsCustomV4);
    }

    public string DnsCustomV4Port
    {
        get => _settings.Current.DnsCustomV4Port.ToString();
        set => SetDnsPort(v => _settings.Current.DnsCustomV4Port = v, value);
    }

    public string DnsCustomV6
    {
        get => _settings.Current.DnsCustomV6;
        set => SetDnsField(v => _settings.Current.DnsCustomV6 = v, value?.Trim() ?? string.Empty, _settings.Current.DnsCustomV6);
    }

    public string DnsCustomV6Port
    {
        get => _settings.Current.DnsCustomV6Port.ToString();
        set => SetDnsPort(v => _settings.Current.DnsCustomV6Port = v, value);
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

    private void SetDnsField(Action<string> apply, string value, string current)
    {
        if (value == current) return;
        apply(value);
        _settings.Save();
        OnCustomDnsChanged();
    }

    private void SetDnsPort(Action<int> apply, string? text)
    {
        if (!int.TryParse(text, out var port)) { DnsWarning = "Port sayi olmali."; return; }
        if (port is < 0 or > 65535) { DnsWarning = "Port 0-65535 araliginda olmali."; return; }
        apply(port);
        _settings.Save();
        OnCustomDnsChanged();
    }

    /// <summary>Ozel DNS alanlari degistiginde profili yeniden kur, dogrula, gerekirse baglantiyi tazele.</summary>
    private void OnCustomDnsChanged()
    {
        RebuildDnsProfiles();

        var custom = _dnsProfiles.FirstOrDefault(p => p.Id == DnsProfile.CustomId);
        DnsWarning = custom?.Validate() ?? string.Empty;

        OnPropertyChanged(nameof(DnsProfiles));
        OnPropertyChanged(nameof(SelectedDns));
        OnPropertyChanged(nameof(ProfileSummary));

        if (SelectedDns.Id == DnsProfile.CustomId && !HasDnsWarning) RestartIfRunning();
    }

    private void RebuildDnsProfiles()
    {
        var custom = DnsProfile.CreateCustom(
            _settings.Current.DnsCustomV4, _settings.Current.DnsCustomV4Port,
            _settings.Current.DnsCustomV6, _settings.Current.DnsCustomV6Port);

        _dnsProfiles = DnsProfile.BuiltIn.Append(custom).ToArray();
    }

    // ------------------------------------------------------------ ozel native ayarlari

    private NativeDpiConfig Cfg => _settings.Current.NativeCustom;

    public int NativeTtl
    {
        get => Cfg.Ttl;
        set => SetNative(() => Cfg.Ttl = Math.Clamp(value, 1, 255), Cfg.Ttl != value);
    }

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

    private void SetNative(Action apply, bool changed, [System.Runtime.CompilerServices.CallerMemberName] string? name = null)
    {
        if (!changed) return;
        apply();
        _settings.Save();
        OnPropertyChanged(name);
        // Ozel profil etkinse degisikligi aninda uygula.
        if (SelectedNativeProfile.Id == NativeProfile.CustomId) RestartIfRunning();
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

    /// <summary>Alt satirda gosterilen ozet: "Motor · Yontem · DNS".</summary>
    public string ProfileSummary
    {
        get
        {
            var engine = IsNativeEngine ? "Kendi motoru" : "GoodbyeDPI";
            var method = IsNativeEngine ? SelectedNativeProfile.Name : SelectedMethod.Name;
            return $"{engine} · {method} · DNS: {SelectedDns.Name}";
        }
    }

    public void ReportStartupProblem(string? error)
    {
        StartupWarning = string.IsNullOrWhiteSpace(error)
            ? "Windows ile baslatma ayarlanamadi."
            : "Windows ile baslatma ayarlanamadi: " + error;
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
    private EngineRequest BuildRequest()
    {
        var native = SelectedNativeProfile.Id == NativeProfile.CustomId
            ? _settings.Current.NativeCustom.Clone()
            : SelectedNativeProfile.Build();

        var dns = DnsProfile.FromId(_settings.Current.Dns, () => _dnsProfiles.First(p => p.Id == DnsProfile.CustomId));

        return new EngineRequest(SelectedMethod, native, dns);
    }

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

    public bool UpdateAvailable
    {
        get => _updateAvailable;
        private set => SetField(ref _updateAvailable, value);
    }

    public string UpdateStatusText
    {
        get => _updateStatus;
        private set => SetField(ref _updateStatus, value);
    }

    public RelayCommand ApplyUpdateCommand { get; }

    /// <summary>
    /// Acilista cagrilir: GitHub'da yeni surum varsa indirir; AutoUpdate acikca
    /// kapatilmadikca kurulumu otomatik baslatir.
    /// </summary>
    public async Task CheckForUpdatesAsync()
    {
        if (!AutoUpdate) return;

        try
        {
            var info = await _updates.CheckAsync();
            if (info is null) return;

            UpdateAvailable = true;
            UpdateStatusText = $"Yeni surum bulundu ({info.Tag}), indiriliyor...";

            var path = await _updates.DownloadAsync(info);
            if (path is null)
            {
                UpdateStatusText = "Guncelleme indirilemedi. Daha sonra tekrar denenecek.";
                return;
            }

            _pendingSetup = path;
            _updateReady = true;
            ApplyUpdateCommand.RaiseCanExecuteChanged();
            UpdateStatusText = $"Guncelleme hazir ({info.Tag}). Kuruluyor...";

            // AutoUpdate acik: kurulumu otomatik baslat (uygulama kapanip guncellenecek).
            ApplyUpdate();
        }
        catch
        {
            // Guncelleme hatalari kullaniciyi engellemez.
        }
    }

    private void ApplyUpdate()
    {
        if (!_updateReady || _pendingSetup is null) return;

        if (UpdateService.LaunchInstaller(_pendingSetup))
        {
            // Baglantiyi birak, kurucu dosyalari degistirebilsin.
            _dpi.Stop();
            RequestShutdown?.Invoke();
        }
        else
        {
            UpdateStatusText = "Kurulum baslatilamadi.";
        }
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

    private void UpdateStatus()
    {
        StatusText = State switch
        {
            ConnectionState.Connected => "Baglandi",
            ConnectionState.Connecting => "Baglaniyor",
            ConnectionState.Failed => "Hata",
            _ => "Kapali",
        };

        StatusDetail = State switch
        {
            ConnectionState.Failed => _dpi.LastError ?? "Bilinmeyen hata.",
            ConnectionState.Connected => ProfileSummary,
            _ => string.Empty,
        };

        OnPropertyChanged(nameof(State));
        OnPropertyChanged(nameof(IsConnected));
        OnPropertyChanged(nameof(IsConnecting));
        OnPropertyChanged(nameof(IsFailed));
    }
}

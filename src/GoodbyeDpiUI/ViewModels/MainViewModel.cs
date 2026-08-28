using System.Windows;
using GoodbyeDpiUI.Models;
using GoodbyeDpiUI.Services;

namespace GoodbyeDpiUI.ViewModels;

public sealed class MainViewModel : ObservableObject
{
    private readonly GoodbyeDpiService _dpi;
    private readonly SettingsService _settings;
    private readonly ThemeService _theme;

    private string _statusText = "Kapali";
    private string _statusDetail = string.Empty;
    private bool _isBusy;
    private bool _areAnimationsEnabled = true;
    private string _startupWarning = string.Empty;

    public MainViewModel(GoodbyeDpiService dpi, SettingsService settings, ThemeService theme)
    {
        _dpi = dpi;
        _settings = settings;
        _theme = theme;

        _dpi.StateChanged += OnStateChanged;

        ToggleConnectionCommand = new RelayCommand(() => _ = ToggleAsync(), () => !IsBusy);

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

    /// <summary>
    /// Pencere goruntulenirken true. Guc dugmesindeki surekli storyboard'lar buna
    /// bagli; tepsiye kucululdugunde animasyon saatleri tamamen durur.
    /// </summary>
    public bool AreAnimationsEnabled
    {
        get => _areAnimationsEnabled;
        set => SetField(ref _areAnimationsEnabled, value);
    }

    // ------------------------------------------------------------ ayarlar

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
                OnPropertyChanged();   // anahtari eski konumuna geri al
                return;
            }

            StartupWarning = string.Empty;
            _settings.Current.RunAtStartup = value;
            _settings.Save();
            OnPropertyChanged();
        }
    }

    /// <summary>Uygulama acilir acilmaz baglantiyi da baslatsin mi?</summary>
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

    public IReadOnlyList<DpiMethod> Methods => DpiMethod.All;

    public IReadOnlyList<DnsProfile> DnsProfiles => DnsProfile.All;

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

    public DnsProfile SelectedDns
    {
        get => DnsProfile.FromId(_settings.Current.Dns);
        set
        {
            if (value is null || value.Id == _settings.Current.Dns) return;
            _settings.Current.Dns = value.Id;
            _settings.Save();
            OnPropertyChanged();
            OnPropertyChanged(nameof(ProfileSummary));
            RestartIfRunning();
        }
    }

    /// <summary>Otomatik baslatma kurulamadiysa dolu olur; ayar panelinde gosterilir.</summary>
    public string StartupWarning
    {
        get => _startupWarning;
        private set
        {
            if (SetField(ref _startupWarning, value)) OnPropertyChanged(nameof(HasStartupWarning));
        }
    }

    public bool HasStartupWarning => !string.IsNullOrEmpty(StartupWarning);

    /// <summary>Alt satirda gosterilen "Varsayilan - Cloudflare" ozeti.</summary>
    public string ProfileSummary => $"{SelectedMethod.Name} · DNS: {SelectedDns.Name}";

    /// <summary>
    /// Gorev Zamanlayici gorevi olusturulamadiginda cagrilir. Kullanici sorunu
    /// yeniden baslatana kadar fark etmesin diye durum satirinda gosteriyoruz.
    /// </summary>
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
            await _dpi.StartAsync(SelectedMethod, SelectedDns);
        }
        finally
        {
            IsBusy = false;
        }
    }

    /// <summary>
    /// Acilistaki otomatik baglanti. Elle baslatmadan farki: basarisiz olursa
    /// birkac kez yeniden dener.
    ///
    /// Bilgisayar acilirken gorev bizi oturumdan 5 saniye sonra baslatiyor; WinDivert
    /// surucusu ya da ag yigini o anda henuz hazir olmayabiliyor. Tek denemede
    /// birakmak, kullaniciyi "acildi ama baglanmadi" durumunda birakirdi.
    /// </summary>
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

    // ---------------------------------------------------------- bildirim

    private void OnStateChanged(object? sender, EventArgs e)
    {
        // Servis olaylari havuz is parcaciklarindan gelir; arayuze gecmeden once
        // dispatcher'a al.
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

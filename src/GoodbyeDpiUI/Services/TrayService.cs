using System.Drawing;
using System.Windows;
using GoodbyeDpiUI.Models;
using GoodbyeDpiUI.ViewModels;
using Forms = System.Windows.Forms;

namespace GoodbyeDpiUI.Services;

/// <summary>
/// Sistem tepsisi simgesi.
///
/// Simge icin WindowsDesktop runtime'inda zaten bulunan Forms.NotifyIcon
/// kullaniliyor (ek paket yok). Ancak tiklaninca acilan panel WinForms
/// ContextMenuStrip degil, uygulamanin temasini ve animasyonlarini paylasan
/// bir WPF penceresi (<see cref="TrayFlyout"/>).
/// </summary>
public sealed class TrayService : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    private readonly MainViewModel _vm;
    private readonly Func<TrayFlyout> _flyoutFactory;

    private TrayFlyout? _flyout;

    public TrayService(MainViewModel vm, Func<TrayFlyout> flyoutFactory)
    {
        _vm = vm;
        _flyoutFactory = flyoutFactory;

        _icon = new Forms.NotifyIcon
        {
            Icon = LoadIcon(),
            Visible = true,
            Text = "GoodbyeDPI UI",
        };

        // Sol ve sag tik ayni paneli acar; ayri bir baglam menusu yok.
        _icon.MouseUp += (_, e) =>
        {
            if (e.Button is Forms.MouseButtons.Left or Forms.MouseButtons.Right)
                Application.Current?.Dispatcher.BeginInvoke(ShowFlyout);
        };

        _vm.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName is nameof(MainViewModel.StatusText) or nameof(MainViewModel.State))
                RefreshTooltip();
        };

        RefreshTooltip();
    }

    private void ShowFlyout()
    {
        // Panel ilk ihtiyac aninda olusturulur; sonra yeniden kullanilir.
        _flyout ??= _flyoutFactory();
        _flyout.ShowNear();
    }

    private static Icon LoadIcon()
    {
        try
        {
            var uri = new Uri("pack://application:,,,/Assets/app.ico", UriKind.Absolute);
            var stream = Application.GetResourceStream(uri)?.Stream;
            if (stream is not null)
            {
                using (stream)
                {
                    // Tepsi 16x16 ister; .ico icindeki uygun boyut secilir.
                    return new Icon(stream, Forms.SystemInformation.SmallIconSize);
                }
            }
        }
        catch
        {
            // Kaynak okunamadi: sistem varsayilanina dus.
        }

        return SystemIcons.Application;
    }

    private void RefreshTooltip()
    {
        // NotifyIcon.Text 63 karakterle sinirli, tasarsa ArgumentException atar.
        var text = "GoodbyeDPI - " + _vm.StatusText;
        _icon.Text = text.Length > 63 ? text[..63] : text;
    }

    /// <summary>Tepsiye kucululdugunde tek seferlik bilgilendirme.</summary>
    public void ShowMinimizedHint()
    {
        try
        {
            _icon.ShowBalloonTip(2000, "GoodbyeDPI UI",
                "Uygulama tepside calismaya devam ediyor.", Forms.ToolTipIcon.Info);
        }
        catch
        {
            // Bildirimler kapaliysa onemsiz.
        }
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}

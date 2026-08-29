using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.WinForms;

namespace Phormi.Desktop;

/// <summary>
/// Windows shell for Phormi. The desktop shell owns native window/tab controls;
/// the WebView2 instances provide the browser surface. Browser state remains
/// compatible with the platform-neutral Phormi sync contract.
/// </summary>
public sealed class MainForm : Form
{
    private const int MaxTabs = 5;
    private const string NewTabUrl = "about:blank";

    private readonly TextBox addressBar = new();
    private readonly Button goButton = new();
    private readonly Button newTabButton = new();
    private readonly TabControl tabs = new();
    private readonly List<DesktopTab> tabState = new();

    public MainForm()
    {
        Text = "Phormi";
        Width = 1200;
        Height = 800;
        StartPosition = FormStartPosition.CenterScreen;

        var toolbar = new Panel { Dock = DockStyle.Top, Height = 44, Padding = new Padding(8) };
        newTabButton.Dock = DockStyle.Left;
        newTabButton.Width = 42;
        newTabButton.Text = "+";
        newTabButton.Click += (_, _) => CreateTab();

        goButton.Dock = DockStyle.Right;
        goButton.Width = 52;
        goButton.Text = "→";
        goButton.Click += (_, _) => Navigate(addressBar.Text);

        addressBar.Dock = DockStyle.Fill;
        addressBar.PlaceholderText = "Search or enter URL";
        addressBar.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                Navigate(addressBar.Text);
                e.SuppressKeyPress = true;
            }
        };

        toolbar.Controls.Add(addressBar);
        toolbar.Controls.Add(goButton);
        toolbar.Controls.Add(newTabButton);

        tabs.Dock = DockStyle.Fill;
        tabs.DrawMode = TabDrawMode.OwnerDrawFixed;
        tabs.ItemSize = new Size(180, 26);
        tabs.Padding = new Point(12, 3);
        tabs.DrawItem += DrawTab;
        tabs.SelectedIndexChanged += (_, _) => SyncToolbarFromActiveTab();
        tabs.MouseDown += TabsMouseDown;

        Controls.Add(tabs);
        Controls.Add(toolbar);

        Shown += async (_, _) =>
        {
            await CreateTabAsync();
        };
    }

    private async void CreateTab()
    {
        if (tabs.TabPages.Count >= MaxTabs)
        {
            MessageBox.Show($"Phormi supports up to {MaxTabs} tabs.", "Tabs", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        await CreateTabAsync();
    }

    private async Task CreateTabAsync(string url = NewTabUrl)
    {
        if (tabs.TabPages.Count >= MaxTabs) return;

        var page = new TabPage("New tab");
        var view = new WebView2 { Dock = DockStyle.Fill };
        var state = new DesktopTab(Guid.NewGuid().ToString("N"), NewTabUrl, "New tab", view, page);

        tabState.Add(state);
        page.Controls.Add(view);
        tabs.TabPages.Add(page);
        tabs.SelectedTab = page;

        await view.EnsureCoreWebView2Async();
        view.NavigationStarting += (_, _) => UpdateTabFromView(state);
        view.NavigationCompleted += (_, _) => UpdateTabFromView(state);
        view.CoreWebView2.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            Navigate(e.Uri);
        };
        view.CoreWebView2.Navigate(url);
        SyncToolbarFromActiveTab();
    }

    private void Navigate(string input)
    {
        var value = input.Trim();
        if (string.IsNullOrWhiteSpace(value)) return;

        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            value = "https://www.google.com/search?q=" + Uri.EscapeDataString(value);
        }

        var active = ActiveTab;
        if (active?.View.CoreWebView2 is null) return;
        active.View.CoreWebView2.Navigate(value);
    }

    private DesktopTab? ActiveTab =>
        tabs.SelectedTab is null ? null : tabState.FirstOrDefault(t => t.Page == tabs.SelectedTab);

    private void UpdateTabFromView(DesktopTab state)
    {
        var source = state.View.Source?.ToString();
        if (!string.IsNullOrWhiteSpace(source)) state.Url = source;

        var title = state.View.CoreWebView2?.DocumentTitle;
        state.Title = string.IsNullOrWhiteSpace(title) ? "New tab" : title.Trim();
        state.Page.Text = ShortTitle(state.Title);

        if (ReferenceEquals(state, ActiveTab)) SyncToolbarFromActiveTab();
    }

    private void SyncToolbarFromActiveTab()
    {
        addressBar.Text = ActiveTab?.Url ?? string.Empty;
    }

    private static string ShortTitle(string title)
    {
        const int max = 24;
        return title.Length <= max ? title : title[..(max - 1)] + "…";
    }

    private void DrawTab(object? sender, DrawItemEventArgs e)
    {
        if (e.Index < 0 || e.Index >= tabs.TabPages.Count) return;
        var page = tabs.TabPages[e.Index];
        TextRenderer.DrawText(e.Graphics, page.Text, Font, e.Bounds, ForeColor,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }

    private void TabsMouseDown(object? sender, MouseEventArgs e)
    {
        if (e.Button != MouseButtons.Middle) return;
        for (var i = 0; i < tabs.TabPages.Count; i++)
        {
            if (!tabs.GetTabRect(i).Contains(e.Location)) continue;
            CloseTab(i);
            return;
        }
    }

    private void CloseTab(int index)
    {
        if (index < 0 || index >= tabs.TabPages.Count) return;
        var page = tabs.TabPages[index];
        var state = tabState.FirstOrDefault(t => t.Page == page);
        if (state is not null)
        {
            state.View.Dispose();
            tabState.Remove(state);
        }
        tabs.TabPages.Remove(page);

        if (tabs.TabPages.Count == 0)
        {
            _ = CreateTabAsync();
        }
        else
        {
            SyncToolbarFromActiveTab();
        }
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        foreach (var state in tabState.ToList()) state.View.Dispose();
        tabState.Clear();
        base.OnFormClosed(e);
    }

    private sealed class DesktopTab
    {
        public DesktopTab(string id, string url, string title, WebView2 view, TabPage page)
        {
            Id = id;
            Url = url;
            Title = title;
            View = view;
            Page = page;
        }

        public string Id { get; }
        public string Url { get; set; }
        public string Title { get; set; }
        public WebView2 View { get; }
        public TabPage Page { get; }
    }
}

using System.Text.Json;
using System.Text.Json.Serialization;

namespace Phormi.Desktop;

/// <summary>
/// Platform-neutral browser state shared by desktop and mobile clients.
/// Credentials, cookies, auth tokens, downloads and private data are absent by design.
/// </summary>
public sealed class SharedBrowserState
{
    public const int CurrentSchema = 1;

    [JsonPropertyName("schema")] public int Schema { get; set; } = CurrentSchema;
    [JsonPropertyName("product")] public string Product { get; set; } = "Phormi";
    [JsonPropertyName("accountId")] public string? AccountId { get; set; }
    [JsonPropertyName("deviceId")] public string? DeviceId { get; set; }
    [JsonPropertyName("updatedAt")] public long UpdatedAt { get; set; }
    [JsonPropertyName("tabs")] public List<SharedTab> Tabs { get; set; } = [];
    [JsonPropertyName("activeTabIndex")] public int ActiveTabIndex { get; set; }
    [JsonPropertyName("searchProvider")] public string SearchProvider { get; set; } = "Phormi Search";
    [JsonPropertyName("themeMode")] public string ThemeMode { get; set; } = "system";
    [JsonPropertyName("dailyAccent")] public bool DailyAccent { get; set; }
    [JsonPropertyName("wallpaperReference")] public string? WallpaperReference { get; set; }
    [JsonPropertyName("keepScreenOn")] public bool KeepScreenOn { get; set; }
    [JsonPropertyName("customShortcuts")] public JsonElement CustomShortcuts { get; set; }

    public static SharedBrowserState CreateEmpty(string deviceId) => new()
    {
        DeviceId = deviceId,
        UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
    };

    public string ToJson() => JsonSerializer.Serialize(this, JsonOptions);

    public static SharedBrowserState? FromJson(string json)
    {
        try
        {
            var state = JsonSerializer.Deserialize<SharedBrowserState>(json, JsonOptions);
            if (state is null || state.Schema != CurrentSchema || state.Product != "Phormi") return null;
            state.Tabs = state.Tabs.Take(5).ToList();
            if (state.Tabs.Count == 0) state.Tabs.Add(new SharedTab());
            state.ActiveTabIndex = Math.Clamp(state.ActiveTabIndex, 0, state.Tabs.Count - 1);
            return state;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };
}

public sealed class SharedTab
{
    [JsonPropertyName("id")] public string Id { get; set; } = "tab-1";
    [JsonPropertyName("url")] public string Url { get; set; } = "about:blank";
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("updatedAt")] public long UpdatedAt { get; set; }
}

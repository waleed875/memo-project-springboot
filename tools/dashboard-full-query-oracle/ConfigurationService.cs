global using Microsoft.Extensions.Logging;

namespace MemoDashboard.Backend.Services;

/// <summary>Test-only disposable connection provider.</summary>
public sealed class ConfigurationService
{
    public string? BuildConnectionString() => Environment.GetEnvironmentVariable(
        "MEMO_DASHBOARD_PARITY_DB");
}

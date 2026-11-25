using System.Text.Json;
using MemoDashboard.Backend.Models;
using MemoDashboard.Backend.Services;
using Microsoft.Extensions.Logging.Abstractions;

if (args.Length != 1)
    throw new ArgumentException("Usage: DashboardFullQueryOracle <output-file>");
if (string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("MEMO_DASHBOARD_PARITY_DB")))
    throw new InvalidOperationException("Disposable DB connection is required.");

var a=Guid.Parse("e2000000-0000-4000-8000-000000000001");
var b=Guid.Parse("e2000000-0000-4000-8000-000000000002");
var scenarios = new (string Name,Guid? Company,string From,string To,string? Status,
    string Basis,string? Cutoff)[] {
    ("company-arrival",a,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    ("company-writing",a,"2026-11-01","2026-11-30",null,"WritingDate",null),
    ("company-status",a,"2026-11-01","2026-11-30","APPROVED","ArrivalDate",null),
    ("company-cutoff",a,"2026-11-01","2026-11-30",null,"ArrivalDate","2026-11-15"),
    ("global-arrival",null,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    ("other-company",b,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    ("empty-window",a,"2027-01-01","2027-01-31",null,"ArrivalDate",null),
    ("prior-period",a,"2026-11-11","2026-11-20",null,"ArrivalDate","2026-11-20")
};
var service=new DashboardQueryService(new ConfigurationService(),
    NullLogger<DashboardQueryService>.Instance);
var data=new SortedDictionary<string,DashboardSnapshot>(StringComparer.Ordinal);
foreach(var c in scenarios) {
    var filters=new FilterQuery {
        CompanyId=c.Company, From=DateTime.Parse(c.From),
        To=DateTime.Parse(c.To), Status=c.Status, DateBasis=c.Basis,
        EntryCutoffDate=c.Cutoff is null ? null : DateTime.Parse(c.Cutoff)
    };
    var snapshot=await service.GetSnapshotAsync(filters,minSample:1);
    if(!snapshot.IsHealthy)
        throw new InvalidOperationException("Original C# query unhealthy for "+c.Name);
    data.Add(c.Name,snapshot);
    Console.WriteLine($"Original C# GetSnapshotAsync: {c.Name}; notes={snapshot.Summary.TotalNotes}");
}
await File.WriteAllTextAsync(args[0],
    JsonSerializer.Serialize(data,new JsonSerializerOptions(JsonSerializerDefaults.Web)));

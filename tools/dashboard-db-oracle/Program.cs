using Dapper;
using Npgsql;
using MemoDashboard.Backend.Models;
using System.Text.Json;
using System.Reflection;
using System.Text.RegularExpressions;

var connection = Environment.GetEnvironmentVariable("MEMO_DASHBOARD_PARITY_DB")
    ?? throw new InvalidOperationException("Disposable PostgreSQL connection is required.");
if(args.Length != 1) throw new ArgumentException("Usage: DashboardDatabaseOracle <output-file>");
var a = Guid.Parse("e2000000-0000-4000-8000-000000000001");
var b = Guid.Parse("e2000000-0000-4000-8000-000000000002");
var cases = new [] {
    new Case("company-arrival",a,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    new Case("company-writing",a,"2026-11-01","2026-11-30",null,"WritingDate",null),
    new Case("company-status",a,"2026-11-01","2026-11-30","APPROVED","ArrivalDate",null),
    new Case("company-cutoff",a,"2026-11-01","2026-11-30",null,"ArrivalDate","2026-11-15"),
    new Case("global-arrival",null,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    new Case("other-company",b,"2026-11-01","2026-11-30",null,"ArrivalDate",null),
    new Case("empty-window",a,"2027-01-01","2027-01-31",null,"ArrivalDate",null),
    new Case("prior-period",a,"2026-11-11","2026-11-20",null,"ArrivalDate","2026-11-20")
};
string Column(string name) => name switch {
    "Late6To10Notes" => "late_6_to_10_notes",
    "Late11To20Notes" => "late_11_to_20_notes",
    "LateOver20Notes" => "late_over_20_notes",
    "ErrorsPer100Checked" => "errors_per_100_checked",
    "P90LateDays" => "p90_late_days",
    "IncidentsPer100Notes" => "incidents_per_100_notes",
    _ => Regex.Replace(name, "([a-z0-9])([A-Z])", "$1_$2").ToLowerInvariant()
};
var fields=typeof(CompanyMetrics).GetProperties(BindingFlags.Public|BindingFlags.Instance);
if(fields.Length!=43) throw new InvalidDataException("Original CompanyMetrics property count changed");
var select=string.Join(",",fields.Select(p => Column(p.Name)+" AS \""+p.Name+"\""));
var sql="select "+select+" from reporting.fn_company_metrics_snapshot_v4_filtered("+
    "@CompanyId,cast(@From as date),cast(@To as date),@FactoryId,@ProductionLineId,@NoteTypeId,@Status,cast(@Cutoff as date),@DateBasis)";
using var db=new NpgsqlConnection(connection);
await db.OpenAsync();
var results=new SortedDictionary<string,object>();
foreach(var c in cases) {
    DateTime? from=DateTime.Parse(c.From),to=DateTime.Parse(c.To);
    var argsNow=new {
       CompanyId=c.Company,From=from,To=to,
       FactoryId=(Guid?)null,ProductionLineId=(Guid?)null,NoteTypeId=(Guid?)null,
       Status=c.Status,Cutoff=c.Cutoff==null?(DateTime?)null:DateTime.Parse(c.Cutoff),
       DateBasis=c.Basis
    };
    CompanyMetrics current=await db.QuerySingleAsync<CompanyMetrics>(sql,argsNow);
    CompanyMetrics? previous=null;
    if(c.Company!=null&&from!=null&&to!=null) {
       int days=(to.Value-from.Value).Days+1;
       DateTime prevTo=from.Value.AddDays(-1),prevFrom=prevTo.AddDays(1-days);
       var argsPrev=new {
         CompanyId=c.Company,From=(DateTime?)prevFrom,To=(DateTime?)prevTo,
         FactoryId=(Guid?)null,ProductionLineId=(Guid?)null,NoteTypeId=(Guid?)null,
         Status=c.Status,Cutoff=c.Cutoff==null?(DateTime?)null:DateTime.Parse(c.Cutoff).AddDays(-days),
         DateBasis=c.Basis
       };
       previous=await db.QuerySingleAsync<CompanyMetrics>(sql,argsPrev);
    }
    results.Add(c.Name,new { current, previous });
}
await File.WriteAllTextAsync(args[0],JsonSerializer.Serialize(results,new JsonSerializerOptions(JsonSerializerDefaults.Web)));
Console.WriteLine("Executed original 43-field C# CompanyMetrics DTO over "+results.Count+" real PostgreSQL filter cases.");
record Case(string Name,Guid? Company,string From,string To,string? Status,string Basis,string? Cutoff);

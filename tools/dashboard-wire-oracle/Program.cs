using System.Text.Json;
using MemoDashboard.Backend.Models;
using MemoDashboard.Backend.Services;

if (args.Length != 2) {
    Console.Error.WriteLine("Usage: DashboardWireOracle <java-snapshot-dir> <csharp-output-dir>");
    return 2;
}
if (!Directory.Exists(args[0])) throw new DirectoryNotFoundException(args[0]);
Directory.CreateDirectory(args[1]);
var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
var files = Directory.GetFiles(args[0], "*.json");
if (files.Length != 3) throw new InvalidDataException($"Expected 3 pinned scenarios, got {files.Length}");
foreach (var path in files.OrderBy(x => x, StringComparer.Ordinal)) {
    var snapshot=JsonSerializer.Deserialize<DashboardSnapshot>(
        await File.ReadAllTextAsync(path),options)
        ?? throw new InvalidDataException("Null legacy snapshot from " + path);
    snapshot.Views=DashboardContractBuilder.Build(snapshot);
    await File.WriteAllTextAsync(
        Path.Combine(args[1],Path.GetFileName(path)),JsonSerializer.Serialize(snapshot,options));
    Console.WriteLine($"C# oracle: {Path.GetFileName(path)}");
}
return 0;

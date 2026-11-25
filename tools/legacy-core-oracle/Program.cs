using System.Globalization;
using System.Text;
using AuditMemoSystem.Core.Enums;
using AuditMemoSystem.Core.Models;
using AuditMemoSystem.Core.Validation;

// The original C# Core library is compiled from a pinned read-only GitHub
// checkout. This oracle does not reimplement its business rules.
if (args.Length != 2)
    throw new ArgumentException("Usage: <shared cases.tsv> <oracle output.tsv>");

static string Decode(string value) => value == "<empty>" ? "" : value;
static DateOnly? Date(string value) =>
    value == "-" ? null : DateOnly.ParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture);
static Guid FieldId(int id) => Guid.Parse($"00000000-0000-0000-0000-{id:000000000000}");

static List<NoteTypeFieldModel> Fields(string spec)
{
    if (spec == "-") return [];
    return spec.Split(',').Select(token =>
    {
        var bits = token.Split(':');
        if (bits.Length != 3) throw new FormatException($"Invalid field: {token}");
        return new NoteTypeFieldModel
        {
            FieldId = FieldId(int.Parse(bits[0], CultureInfo.InvariantCulture)),
            FieldNameAr = "Field " + bits[0],
            IsRequired = bits[1] == "R",
            IsActive = bits[2] == "A"
        };
    }).ToList();
}
static List<NoteFieldValueModel> Values(string spec)
{
    if (spec == "-") return [];
    return spec.Split(',').Select(token =>
    {
        var bits = token.Split(':');
        if (bits.Length != 2) throw new FormatException($"Invalid indicator: {token}");
        return new NoteFieldValueModel {
            FieldId = FieldId(int.Parse(bits[0], CultureInfo.InvariantCulture)),
            FieldValue = Enum.Parse<IndicatorValue>(bits[1])
        };
    }).ToList();
}

static string Execute(string[] row)
{
    var kind = row[1];
    var a = row[2];
    var b = row[3];
    var c = row[4];
    var d = row[5];
    var e = row[6];
    switch (kind)
    {
        case "normalize":
            return NoteNumberNormalizer.Normalize(Decode(a));
        case "positive":
            return NoteNumberNormalizer.TryParsePositive(Decode(a), out var number)
                ? number.ToString(CultureInfo.InvariantCulture) : "NONE";
        case "delay":
        {
            var writing = Date(a);
            var arrival = Date(b) ?? throw new FormatException("Arrival is required");
            string dayCount = NoteDelayCalculator.DaysBetween(writing, arrival)?.ToString(CultureInfo.InvariantCulture) ?? "-";
            string excess = NoteDelayCalculator.ExcessDays(writing, arrival)?.ToString(CultureInfo.InvariantCulture) ?? "-";
            return $"{NoteDelayCalculator.Classify(writing, arrival)};{dayCount};{excess}";
        }
        case "status":
            return NoteValidator.CalculateStatus(Fields(a), Values(b)).ToString();
        case "edit":
        {
            var role = Enum.Parse<UserRole>(a);
            var status = Enum.Parse<NoteStatus>(b);
            try
            {
                var memo = new NoteModel { Status = status };
                NoteValidator.EnsureApprovedEditAllowed(memo, role);
                NoteValidator.EnsureDraftSaveAllowed(memo, bool.Parse(c));
                return "ALLOW";
            }
            catch (UnauthorizedAccessException) { return "REJECT"; }
            catch (InvalidOperationException) { return "REJECT"; }
        }
        case "validateSubmit":
        case "validateDraft":
        {
            var note = new NoteModel
            {
                NoteNumber = Decode(a),
                CompanyId = Guid.Parse("00000000-0000-0000-0000-000000000011"),
                NoteTypeId = Guid.Parse("00000000-0000-0000-0000-000000000012"),
                WritingDate = Date(b),
                ArrivalAt = Date(c) ?? default,
                FieldValues = Values(e)
            };
            try
            {
                // Legacy production preflight provides effective active definitions.
                NoteValidator.Validate(note, Fields(d).Where(f => f.IsActive).ToList(),
                    validateRequiredFields: kind == "validateSubmit");
                return "ALLOW";
            }
            catch (InvalidOperationException) { return "REJECT"; }
        }
        case "attachment":
            try
            {
                AttachmentFilePolicy.Validate(a,
                    b == "-" ? Array.Empty<byte>() : Convert.FromHexString(b));
                return "ALLOW";
            }
            catch (InvalidOperationException) { return "REJECT"; }
        default:
            throw new InvalidOperationException($"Unknown fixture kind: {kind}");
    }
}
var lines = File.ReadAllLines(args[0], Encoding.UTF8);
var output = new List<string>();
var ids = new HashSet<string>(StringComparer.Ordinal);
foreach (string line in lines)
{
    if (string.IsNullOrWhiteSpace(line) || line.StartsWith('#')) continue;
    var row = line.Split('|');
    if (row.Length != 7) throw new FormatException($"Expected 7 fixture columns: {line}");
    if (!ids.Add(row[0])) throw new FormatException($"Duplicate case ID: {row[0]}");
    output.Add(row[0] + "\t" + Execute(row));
}
if (output.Count < 35) throw new InvalidOperationException("Oracle fixture unexpectedly small");
Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(args[1]))!);
File.WriteAllLines(args[1], output, new UTF8Encoding(false));
Console.WriteLine($"Original C# golden oracle produced {output.Count} test outcomes.");

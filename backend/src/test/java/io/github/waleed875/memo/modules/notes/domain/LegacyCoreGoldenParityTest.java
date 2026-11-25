package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;
import io.github.waleed875.memo.sharedkernel.identity.MemoRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Execute one shared case set; CI compares to the pinned real C# assembly. */
class LegacyCoreGoldenParityTest {
    record Case(String id, String kind, String a, String b, String c, String d, String e) {}
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Test void actualSpringResultsMustMatchOriginalCSharpOracle() throws Exception {
        String data = new String(new ClassPathResource(
            "compatibility/legacy-core-cases.tsv").getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        Map<String,String> actual = new LinkedHashMap<>();
        for (String line : data.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\|", -1);
            assertEquals(7, p.length, "Malformed fixture " + line);
            assertNull(actual.put(p[0], evaluate(new Case(
                p[0],p[1],p[2],p[3],p[4],p[5],p[6]))), "Duplicate fixture ID");
        }
        assertTrue(actual.size() >= 35);
        String oracle = System.getenv("MEMO_CSHARP_ORACLE");
        if (oracle == null || oracle.isBlank()) return; // local Maven fallback
        assertTrue(Files.isRegularFile(Path.of(oracle)), "C# oracle output is required by CI");
        Map<String,String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(oracle), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", -1);
            assertEquals(2, p.length, "Malformed C# output " + line);
            assertNull(expected.put(p[0],p[1]), "Duplicate oracle result");
        }
        assertEquals(expected.keySet(), actual.keySet(), "Reference cases differ");
        List<String> errors = new ArrayList<>();
        for (String key : actual.keySet())
            if (!actual.get(key).equals(expected.get(key)))
                errors.add(key + " expected C#=" + expected.get(key)
                    + " actual Java=" + actual.get(key));
        assertTrue(errors.isEmpty(), "Core business-rule mismatches:\n" + String.join("\n",errors));
    }
    static String evaluate(Case x) {
        return switch(x.kind()) {
            case "normalize" -> NoteNumberNormalizer.normalize(decoded(x.a()));
            case "positive" -> {
                var n=NoteNumberNormalizer.positiveNumericValue(decoded(x.a()));
                yield n.isPresent()?Long.toString(n.getAsLong()):"NONE";
            }
            case "delay" -> delay(x);
            case "status" -> NoteSavePolicy.calculateStatus(fields(x.a()), values(x.b()),false).name();
            case "edit" -> {
                try {
                    NoteSavePolicy.ensureEditAllowed(
                        MemoRole.valueOf(x.a()),NoteStatus.valueOf(x.b()),
                        Boolean.parseBoolean(x.c()));
                    yield "ALLOW";
                } catch(NoteSaveValidationException ex){yield "REJECT";}
            }
            case "validateSubmit","validateDraft" -> {
                var c=new NoteSaveCandidate(decoded(x.a()),
                    UUID.fromString("00000000-0000-0000-0000-000000000011"),
                    UUID.fromString("00000000-0000-0000-0000-000000000012"),
                    null,null,date(x.b()),date(x.c()),
                    x.kind().equals("validateDraft"),false,values(x.e()));
                try {NoteSavePolicy.validate(c,fields(x.d()),TODAY);yield "ALLOW";}
                catch(NoteSaveValidationException ex){yield "REJECT";}
            }
            case "attachment" -> {
                byte[] data=x.b().equals("-")?new byte[0]:
                    HexFormat.of().parseHex(x.b());
                try {NoteAttachmentPolicy.validate(new NoteAttachment(x.a(),data));yield "ALLOW";}
                catch(NoteSaveValidationException ex){yield "REJECT";}
            }
            default -> throw new IllegalArgumentException(x.kind());
        };
    }
    static String decoded(String s) {return s.equals("<empty>")?"":s;}
    static LocalDate date(String s){return s.equals("-")?null:LocalDate.parse(s);}
    static String delay(Case x){
        LocalDate writing=date(x.a()),arrival=date(x.b());
        if(writing==null)return "MISSING_WRITING_DATE;-;-";
        long days=ChronoUnit.DAYS.between(writing,arrival);
        String status = DelayCalculator.classify(
            writing.atStartOfDay(), arrival.atStartOfDay()).name();
        if ("INVALID_DATE_ORDER".equals(status)) status = "INVALID_DATES";
        if ("UNKNOWN".equals(status)) status = "MISSING_WRITING_DATE";
        return status+";"+days+";"+Math.max(0,days-5);
    }
    static UUID fieldId(int n) {
        return UUID.fromString(String.format(Locale.ROOT,
            "00000000-0000-0000-0000-%012d",n));
    }
    static List<RequiredNoteField> fields(String input){
        if(input.equals("-"))return List.of();
        List<RequiredNoteField> all=new ArrayList<>();
        for(String token:input.split(",")){
            String[] p=token.split(":");
            all.add(new RequiredNoteField(fieldId(Integer.parseInt(p[0])),
                "Field "+p[0],p[1].equals("R"),p[2].equals("A")));
        }
        return all;
    }
    static List<NoteSaveCandidate.IndicatorInput> values(String input){
        if(input.equals("-"))return List.of();
        List<NoteSaveCandidate.IndicatorInput> all=new ArrayList<>();
        for(String token:input.split(",")){
            String[] p=token.split(":");
            all.add(new NoteSaveCandidate.IndicatorInput(
                fieldId(Integer.parseInt(p[0])),IndicatorValue.valueOf(p[1])));
        }
        return all;
    }
}

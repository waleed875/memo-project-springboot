package io.github.waleed875.memo.modules.notes.domain;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Signature and 10 MiB checks from C# AttachmentFilePolicy.
 * Additional defense: filename must be a safe basename (no traversal).
 * Magic bytes do not replace antivirus or deep PDF validation.
 */
public final class NoteAttachmentPolicy {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of(
        ".png", "image/png",
        ".jpg", "image/jpeg",
        ".jpeg", "image/jpeg",
        ".bmp", "image/bmp",
        ".gif", "image/gif",
        ".tif", "image/tiff",
        ".tiff", "image/tiff",
        ".pdf", "application/pdf");

    private NoteAttachmentPolicy() {}

    public static String validate(NoteAttachment attachment) {
        if (attachment == null) return null;
        String name = attachment.fileName();
        if (name == null || name.isBlank() || name.length() > 255
            || name.contains("/") || name.contains("\\")
            || name.equals(".") || name.equals("..")
            || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new NoteSaveValidationException("اسم المرفق غير صالح.");
        }
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        String type = TYPES.get(ext);
        if (type == null) {
            throw new NoteSaveValidationException("الملفات المدعومة هي الصور وملفات PDF فقط.");
        }
        byte[] bytes = attachment.bytes();
        if (bytes == null || bytes.length == 0) {
            throw new NoteSaveValidationException("الملف المختار فارغ أو تعذر قراءته.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new NoteSaveValidationException("حجم المرفق يتجاوز الحد المسموح (10 ميغابايت).");
        }
        boolean valid = switch (ext) {
            case ".png" -> starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case ".jpg", ".jpeg" -> starts(bytes, 0xff, 0xd8, 0xff);
            case ".gif" -> ascii(bytes, "GIF87a") || ascii(bytes, "GIF89a");
            case ".bmp" -> ascii(bytes, "BM");
            case ".tif", ".tiff" -> starts(bytes, 0x49,0x49,0x2a,0)
                                || starts(bytes, 0x4d,0x4d,0,0x2a);
            case ".pdf" -> ascii(bytes, "%PDF-") && hasPdfEof(bytes);
            default -> false;
        };
        if (!valid) {
            throw new NoteSaveValidationException("محتوى المرفق لا يطابق نوع الملف المحدد.");
        }
        return type;
    }

    private static boolean ascii(byte[] bytes, String value) {
        return starts(bytes, toInts(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private static int[] toInts(byte[] bytes) {
        int[] ints = new int[bytes.length];
        for (int i = 0; i <bytes.length; i++) ints[i] = bytes[i] & 0xff;
        return ints;
    }

    private static boolean starts(byte[] bytes, int... magic) {
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[i] & 0xff) != magic[i]) return false;
        }
        return true;
    }

    private static boolean hasPdfEof(byte[] bytes) {
        int start = Math.max(0, bytes.length - 1024);
        for (int i = start; i <= bytes.length - 5; i++) {
            if (bytes[i] == '%' && bytes[i+1] == '%' && bytes[i+2] == 'E'
                && bytes[i+3] == 'O' && bytes[i+4] == 'F') return true;
        }
        return false;
    }
}

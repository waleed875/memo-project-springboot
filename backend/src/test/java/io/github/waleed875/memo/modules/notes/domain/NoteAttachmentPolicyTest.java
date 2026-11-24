package io.github.waleed875.memo.modules.notes.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NoteAttachmentPolicyTest {
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test void acceptsValidPdfAndImageSignatures() {
        assertEquals("application/pdf", NoteAttachmentPolicy.validate(
            new NoteAttachment("scan.pdf", bytes("%PDF-1.7\n%%EOF\n"))));
        assertEquals("image/png", NoteAttachmentPolicy.validate(
            new NoteAttachment("image.PNG",
                new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a})));
        assertEquals("image/jpeg", NoteAttachmentPolicy.validate(
            new NoteAttachment("a.jpeg", new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff})));
        assertNull(NoteAttachmentPolicy.validate(null));
    }

    @Test void rejectsUnknownExtensionsAndFalseMagicBytes() {
        assertThrows(NoteSaveValidationException.class, () ->
            NoteAttachmentPolicy.validate(new NoteAttachment("unsafe.svg", bytes("<svg/>"))));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteAttachmentPolicy.validate(new NoteAttachment("false.pdf", bytes("not a PDF"))));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteAttachmentPolicy.validate(new NoteAttachment("noeof.pdf", bytes("%PDF-1.7"))));
    }

    @Test void rejectsOversizedAndEmptyAttachments() {
        assertThrows(NoteSaveValidationException.class, () ->
            NoteAttachmentPolicy.validate(new NoteAttachment("blank.png", new byte[0])));
        assertThrows(NoteSaveValidationException.class, () ->
            NoteAttachmentPolicy.validate(new NoteAttachment(
                "big.png", new byte[NoteAttachmentPolicy.MAX_BYTES + 1])));
    }

    @Test void deniesTraversalAndClonesArrayForFingerprintStability() {
        for (String invalid : new String[]{"../x.pdf", "dir/x.pdf", "dir\\x.pdf", "..", "x\n.pdf"}) {
            assertThrows(NoteSaveValidationException.class, () ->
                NoteAttachmentPolicy.validate(new NoteAttachment(invalid,
                    bytes("%PDF-1.4\n%%EOF\n"))));
        }
        byte[] original = bytes("%PDF-1.4\n%%EOF\n");
        var attachment = new NoteAttachment("ok.pdf", original);
        original[0] = 0;
        byte[] fromGetter = attachment.bytes();
        fromGetter[0] = 0;
        assertEquals("application/pdf", NoteAttachmentPolicy.validate(attachment));
    }
}

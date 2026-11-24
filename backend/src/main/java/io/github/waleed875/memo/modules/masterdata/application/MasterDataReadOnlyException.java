package io.github.waleed875.memo.modules.masterdata.application;
public final class MasterDataReadOnlyException extends RuntimeException {
    public MasterDataReadOnlyException() {
        super("النظام يعمل في وضع القراءة فقط. لا يمكن تنفيذ عمليات التعديل.");
    }
}

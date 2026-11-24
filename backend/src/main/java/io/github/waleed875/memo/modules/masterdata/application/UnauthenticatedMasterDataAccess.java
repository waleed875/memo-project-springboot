package io.github.waleed875.memo.modules.masterdata.application;

/** No user profile: refuse to query or reveal master data. */
public final class UnauthenticatedMasterDataAccess extends RuntimeException {}

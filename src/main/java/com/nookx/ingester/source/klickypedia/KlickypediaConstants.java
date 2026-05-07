package com.nookx.ingester.source.klickypedia;

public final class KlickypediaConstants {

    public static final String SOURCE_CODE = "klickypedia";
    public static final String INGEST_TARGET_CODE = "klickypedia-sets";
    public static final String PAGE_TYPE_SET_LIST = "SET_LIST";
    public static final String PAGE_TYPE_SET_DETAIL = "SET_DETAIL";

    static final String BASE_URL = "https://www.klickypedia.com";
    static final String ALL_SETS_URL = BASE_URL + "/sets/";
    static final String THEMES_PATH = "/themes/";
    static final String SETS_PATH = "/sets/";

    private KlickypediaConstants() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }
}

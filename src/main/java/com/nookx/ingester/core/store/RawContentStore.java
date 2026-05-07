package com.nookx.ingester.core.store;

public interface RawContentStore {

    String buildPath(String sourceCode, String pageType, String naturalKey, String fallbackKey);

    String store(String sourceCode, String pageType, String naturalKey, String fallbackKey, byte[] bytes);

    byte[] read(String storagePath);

    boolean exists(String storagePath);
}

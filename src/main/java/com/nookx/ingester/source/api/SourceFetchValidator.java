package com.nookx.ingester.source.api;

import java.util.Optional;

public interface SourceFetchValidator {

    String sourceCode();

    Optional<String> invalidFetchReason(String pageType, String url, byte[] body);
}

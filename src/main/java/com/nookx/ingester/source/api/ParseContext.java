package com.nookx.ingester.source.api;

public record ParseContext(String sourceCode, String pageType, String url, String naturalKey, String htmlContent) {
}

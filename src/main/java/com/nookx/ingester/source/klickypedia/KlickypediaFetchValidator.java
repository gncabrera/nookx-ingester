package com.nookx.ingester.source.klickypedia;

import com.nookx.ingester.source.api.SourceFetchValidator;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class KlickypediaFetchValidator implements SourceFetchValidator {

    private static final String WP_CRITICAL_ERROR_TEXT = "there has been a critical error on this website";
    private static final String WP_ERROR_PAGE_MARKER = "id=\"error-page\"";
    private static final String WP_ERROR_TITLE_MARKER = "<title>wordpress";
    private static final String FETCH_ERROR_REASON = "Klickypedia returned WordPress critical error page";

    @Override
    public String sourceCode() {
        return KlickypediaConstants.SOURCE_CODE;
    }

    @Override
    public Optional<String> invalidFetchReason(final String pageType, final String url, final byte[] body) {
        if (!KlickypediaConstants.PAGE_TYPE_SET_DETAIL.equals(pageType)) {
            return Optional.empty();
        }
        if (url == null || body == null || body.length == 0) {
            return Optional.empty();
        }
        final String normalizedUrl = url.toLowerCase(Locale.ROOT);
        if (!normalizedUrl.contains("klickypedia.com")) {
            return Optional.empty();
        }
        final String html = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (html.contains(WP_CRITICAL_ERROR_TEXT)) {
            return Optional.of(FETCH_ERROR_REASON);
        }
        final boolean hasErrorPageMarker = html.contains(WP_ERROR_PAGE_MARKER);
        final boolean hasWordpressErrorTitle = html.contains(WP_ERROR_TITLE_MARKER) && html.contains("error");
        if (hasErrorPageMarker && hasWordpressErrorTitle) {
            return Optional.of(FETCH_ERROR_REASON);
        }
        return Optional.empty();
    }
}

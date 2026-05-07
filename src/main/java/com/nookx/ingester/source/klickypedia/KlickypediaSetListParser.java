package com.nookx.ingester.source.klickypedia;

import com.nookx.ingester.ingest.klickypediasets.NormalizedSetPayload;
import com.nookx.ingester.source.api.PageParser;
import com.nookx.ingester.source.api.ParseContext;
import com.nookx.ingester.source.api.ParseResult;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
public class KlickypediaSetListParser implements PageParser<NormalizedSetPayload> {

    private static final Pattern PAGE_PATH = Pattern.compile("^/themes/([^/]+)/page/(\\d+)/?$");
    private static final Pattern THEME_PATH = Pattern.compile("^/themes/([^/]+)/?.*$");

    @Override
    public String pageType() {
        return KlickypediaConstants.PAGE_TYPE_SET_LIST;
    }

    @Override
    public Class<NormalizedSetPayload> payloadType() {
        return NormalizedSetPayload.class;
    }

    @Override
    public ParseResult<NormalizedSetPayload> parse(final ParseContext context) {
        final Document doc = Jsoup.parse(context.htmlContent(), KlickypediaConstants.BASE_URL);
        final Map<String, String> setSlugToUrl = new LinkedHashMap<>();
        for (final Element anchor : doc.select("a[itemprop=url][href*='" + KlickypediaConstants.SETS_PATH + "']")) {
            final String href = anchor.attr("abs:href");
            final String slug = extractSetSlug(href);
            if (slug != null && !setSlugToUrl.containsKey(slug)) {
                setSlugToUrl.put(slug, href);
            }
        }
        final Set<String> paginationUrls = extractPagination(doc, context.url());
        final List<DiscoveredUrl> urls = new ArrayList<>(setSlugToUrl.size() + paginationUrls.size());
        for (final Map.Entry<String, String> entry : setSlugToUrl.entrySet()) {
            urls.add(new DiscoveredUrl(entry.getValue(), KlickypediaConstants.PAGE_TYPE_SET_DETAIL, entry.getKey()));
        }
        for (final String pageUrl : paginationUrls) {
            urls.add(new DiscoveredUrl(pageUrl, KlickypediaConstants.PAGE_TYPE_SET_LIST, null));
        }
        return ParseResult.ofUrls(urls);
    }

    private static String extractSetSlug(final String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        final int index = url.indexOf(KlickypediaConstants.SETS_PATH);
        if (index < 0) {
            return null;
        }
        String tail = url.substring(index + KlickypediaConstants.SETS_PATH.length());
        final int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(0, slash);
        }
        final int query = tail.indexOf('?');
        if (query >= 0) {
            tail = tail.substring(0, query);
        }
        tail = tail.trim().toLowerCase(Locale.ROOT);
        return tail.isBlank() ? null : tail;
    }

    private static Set<String> extractPagination(final Document doc, final String currentUrl) {
        final Set<String> pageUrls = new LinkedHashSet<>();
        final String currentTheme = themeSlugOf(currentUrl);
        if (currentTheme == null) {
            return pageUrls;
        }
        final String currentCanonical = canonicalize(currentUrl);
        final Elements anchors = doc.select("a.page[href]");
        for (final Element anchor : anchors) {
            final String href = anchor.attr("abs:href");
            if (href == null || href.isBlank() || !href.startsWith(KlickypediaConstants.BASE_URL)) {
                continue;
            }
            final String canonical = canonicalize(href);
            if (canonical == null) {
                continue;
            }
            final String path = canonical.substring(KlickypediaConstants.BASE_URL.length());
            final Matcher matcher = PAGE_PATH.matcher(path);
            if (!matcher.matches()) {
                continue;
            }
            if (!currentTheme.equals(matcher.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (canonical.equals(currentCanonical)) {
                continue;
            }
            pageUrls.add(canonical);
        }
        return pageUrls;
    }

    private static String canonicalize(final String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        final int hash = trimmed.indexOf('#');
        if (hash >= 0) {
            trimmed = trimmed.substring(0, hash);
        }
        final int query = trimmed.indexOf('?');
        if (query >= 0) {
            trimmed = trimmed.substring(0, query);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String themeSlugOf(final String url) {
        if (url == null || !url.startsWith(KlickypediaConstants.BASE_URL)) {
            return null;
        }
        String path = url.substring(KlickypediaConstants.BASE_URL.length());
        final int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        final Matcher matcher = THEME_PATH.matcher(path);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }
}

package com.nookx.ingester.source.klickypedia;

import com.nookx.ingester.core.http.FetchResult;
import com.nookx.ingester.core.http.ScraperHttpClient;
import com.nookx.ingester.source.api.SourceDiscoverer;
import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KlickypediaDiscoverer implements SourceDiscoverer {

    private final ScraperHttpClient httpClient;

    @Override
    public List<DiscoveredUrl> discover() {
        final FetchResult result = httpClient.get(KlickypediaConstants.ALL_SETS_URL);
        if (!result.isOk()) {
            return List.of();
        }
        final Set<ThemeRef> themes = extractThemes(new String(result.body(), StandardCharsets.UTF_8));
        final List<DiscoveredUrl> urls = new ArrayList<>(themes.size());
        for (final ThemeRef theme : themes) {
            urls.add(new DiscoveredUrl(theme.url(), KlickypediaConstants.PAGE_TYPE_SET_LIST, theme.slug()));
        }
        return urls;
    }

    private static Set<ThemeRef> extractThemes(final String html) {
        final Document doc = Jsoup.parse(html, KlickypediaConstants.BASE_URL);
        final Elements links = doc.select("a[itemprop=url][href*='" + KlickypediaConstants.THEMES_PATH + "']");
        final Set<ThemeRef> themes = new LinkedHashSet<>();
        for (final Element link : links) {
            final String href = link.attr("abs:href");
            if (href == null || href.isBlank()) {
                continue;
            }
            final String slug = extractSlug(href);
            if (slug == null) {
                continue;
            }
            themes.add(new ThemeRef(href, slug));
        }
        return themes;
    }

    private static String extractSlug(final String url) {
        final int index = url.indexOf(KlickypediaConstants.THEMES_PATH);
        if (index < 0) {
            return null;
        }
        String tail = url.substring(index + KlickypediaConstants.THEMES_PATH.length());
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

    private record ThemeRef(String url, String slug) {}
}

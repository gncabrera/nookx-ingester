package com.nookx.ingester.source.klickypedia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nookx.ingester.domain.enumeration.AssetKind;
import com.nookx.ingester.ingest.klickypediasets.NormalizedSetPayload;
import com.nookx.ingester.source.api.PageParser;
import com.nookx.ingester.source.api.ParseContext;
import com.nookx.ingester.source.api.ParseResult;
import com.nookx.ingester.source.api.dto.NormalizedAssetDto;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KlickypediaSetDetailParser implements PageParser<NormalizedSetPayload> {

    private static final String FLAG_EN = "flag-greatbritain";
    private static final String FLAG_ES = "flag-spain";
    private static final String FLAG_DE = "flag-germany";
    private static final String FLAG_FR = "flag-france";
    private static final String RELEASED_KEY = "released";
    private static final Pattern FULL_DATE_PATTERN = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern SET_NUMBER_PREFIX = Pattern.compile("^(\\d+(?:[vs]\\d+)?(?:-\\d+)*)");
    private static final String ONE_TWO_THREE_SUFFIX = "-1-2-3";
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ObjectMapper objectMapper;

    @Override
    public String pageType() {
        return KlickypediaConstants.PAGE_TYPE_SET_DETAIL;
    }

    @Override
    public Class<NormalizedSetPayload> payloadType() {
        return NormalizedSetPayload.class;
    }

    @Override
    public ParseResult<NormalizedSetPayload> parse(final ParseContext context) {
        final Document doc = Jsoup.parse(context.htmlContent(), KlickypediaConstants.BASE_URL);
        final String slug = extractSlug(context.url(), context.naturalKey());
        if (slug == null) {
            return ParseResult.empty();
        }
        final String setNumber = extractSetNumber(slug);
        final Element infoBlock = doc.selectFirst("div.caja_set_info");
        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, String> labelled = new LinkedHashMap<>();
        if (infoBlock != null) {
            extractInfoBlock(infoBlock, names, labelled);
        }
        final String tags = extractTags(doc);
        final List<NormalizedAssetDto> assets = extractImages(doc);
        final String name = firstNonBlank(names.get("name"), labelled.get("name"));
        final LocalDate releaseDate = parseReleaseDate(labelled.get(RELEASED_KEY));
        final ObjectNode rawAttributes = buildRawAttributes(names, labelled, tags);
        final NormalizedSetPayload payload = new NormalizedSetPayload(
            slug,
            setNumber,
            name,
            null,
            releaseDate,
            null,
            rawAttributes,
            assets
        );
        return ParseResult.ofPayload(payload);
    }

    private static List<String> EMPTY_VALUES = List.of("n/a", "(n/a)", "none");
    private ObjectNode buildRawAttributes(final Map<String, String> names, final Map<String, String> labelled, final String tags) {
        final ObjectNode node = objectMapper.createObjectNode();
        for (final Map.Entry<String, String> entry : names.entrySet()) {
            if ("name".equals(entry.getKey())) {
                continue;
            }

            String value = entry.getValue();
            if(entry.getValue() != null && EMPTY_VALUES.contains(entry.getValue().toLowerCase())) {
                value = "";
            }
            node.put(entry.getKey(), value);
        }
        for (final Map.Entry<String, String> entry : labelled.entrySet()) {
            if ("name".equals(entry.getKey()) || RELEASED_KEY.equals(entry.getKey())) {
                continue;
            }
            String value = entry.getValue();
            if(entry.getValue() != null && EMPTY_VALUES.contains(entry.getValue().toLowerCase())) {
                value = "";
            }
            node.put(entry.getKey(), value);
        }
        if (tags != null && !tags.isBlank()) {
            node.put("tags", tags);
        }
        return node;
    }

/**
 * 
 56-32-65-asdasd > 56-32-65
5454 > 5454
5454-abc > 5454
abc > abc
abc-123 > abc-123
9421v1-automata > 9421v1
3573v3-2-cyclists > 3573v3-2
3172s1-security-check-in > 3172s1

Si el setNumber termina siendo solo ceros, devolver todo excepto los ceros:
0000-ger-luv-construction-worker > ger-luv-construction-worker
00000-ger-playmobil-comic-1-2018-heft-29-a-thief-at-the-construction-area > ger-playmobil-comic-1-2018-heft-29-a-thief-at-the-construction-area 

Hay tipos de sets que se llaman 1-2-3, en ese caso ignorar los numeros 1-2-3
5497-1-2-3-advent-calendar-christmas-in-the-forest > 5497
 */
    private static String extractSetNumber(final String slug) {
        if (slug == null || slug.isEmpty() || !Character.isDigit(slug.charAt(0))) {
            return slug;
        }
        final Matcher matcher = SET_NUMBER_PREFIX.matcher(slug);
        if (!matcher.find()) {
            return slug;
        }
        final String matched = matcher.group(1);
        if (isOnlyZeros(matched)) {
            final String remainder = slug.substring(matcher.end());
            if (remainder.startsWith("-")) {
                return remainder.substring(1);
            }
            return remainder;
        }
        if (matched.endsWith(ONE_TWO_THREE_SUFFIX)) {
            return matched.substring(0, matched.length() - ONE_TWO_THREE_SUFFIX.length());
        }
        return matched;
    }

    private static boolean isOnlyZeros(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character != '0' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static String extractSlug(final String url, final String naturalKey) {
        if (naturalKey != null && !naturalKey.isBlank()) {
            return naturalKey.toLowerCase(Locale.ROOT);
        }
        if (url == null) {
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

    private static void extractInfoBlock(
        final Element infoBlock,
        final Map<String, String> names,
        final Map<String, String> labelled
    ) {
        final List<Node> buffer = new ArrayList<>();
        for (final Node child : infoBlock.childNodes()) {
            if (child instanceof Element element && "br".equalsIgnoreCase(element.tagName())) {
                flushBlock(buffer, names, labelled);
                buffer.clear();
                continue;
            }
            buffer.add(child);
        }
        flushBlock(buffer, names, labelled);
    }

    private static void flushBlock(final List<Node> buffer, final Map<String, String> names, final Map<String, String> labelled) {
        if (buffer.isEmpty()) {
            return;
        }
        if (handleFlagBlock(buffer, names)) {
            return;
        }
        handleLabelledBlock(buffer, labelled);
    }

    private static boolean handleFlagBlock(final List<Node> buffer, final Map<String, String> names) {
        Element flag = null;
        for (final Node node : buffer) {
            if (node instanceof Element element && "img".equalsIgnoreCase(element.tagName()) && element.attr("src").contains("flag-")) {
                flag = element;
                break;
            }
        }
        if (flag == null) {
            return false;
        }
        final String key = mapFlagToKey(flag.attr("src").toLowerCase(Locale.ROOT));
        if (key == null) {
            return false;
        }
        final String text = collectPlainText(buffer, flag);
        if (!text.isBlank() && !names.containsKey(key)) {
            names.put(key, text);
        }
        return true;
    }

    private static String mapFlagToKey(final String source) {
        if (source.contains(FLAG_EN)) {
            return "name";
        }
        if (source.contains(FLAG_ES)) {
            return "nameES";
        }
        if (source.contains(FLAG_DE)) {
            return "nameDE";
        }
        if (source.contains(FLAG_FR)) {
            return "nameFR";
        }
        return null;
    }

    private static void handleLabelledBlock(final List<Node> buffer, final Map<String, String> labelled) {
        Element labelStrong = null;
        for (final Node node : buffer) {
            if (node instanceof Element element && "strong".equalsIgnoreCase(element.tagName())) {
                labelStrong = element;
                break;
            }
        }
        if (labelStrong == null) {
            return;
        }
        final String key = normalizeLabel(labelStrong.text());
        if (key == null || key.isBlank()) {
            return;
        }
        final String value = extractValue(buffer, labelStrong);
        if (value != null && !value.isBlank() && !labelled.containsKey(key)) {
            labelled.put(key, value);
        }
    }

    private static String normalizeLabel(final String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final String[] parts = trimmed.split("\\s+");
        final StringBuilder output = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            final String part = parts[i].toLowerCase(Locale.ROOT);
            if (i == 0) {
                output.append(part);
                continue;
            }
            output.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return output.toString();
    }

    private static String extractValue(final List<Node> buffer, final Element labelStrong) {
        boolean afterLabel = false;
        String anchorValue = null;
        String contadorValue = null;
        final StringBuilder textValue = new StringBuilder();
        for (final Node node : buffer) {
            if (!afterLabel) {
                if (node == labelStrong) {
                    afterLabel = true;
                }
                continue;
            }
            if (node instanceof Element element) {
                final String tag = element.tagName().toLowerCase(Locale.ROOT);
                if ("a".equals(tag) && !isEditAnchor(element)) {
                    if (anchorValue == null) {
                        final String text = element.text().trim();
                        if (!text.isEmpty()) {
                            anchorValue = text;
                        }
                    }
                } else if ("span".equals(tag) && element.hasClass("sets-contador")) {
                    if (contadorValue == null) {
                        contadorValue = stripOuterParens(element.text().trim());
                    }
                } else if (!"a".equals(tag) && !"i".equals(tag)) {
                    final String text = element.text().trim();
                    if (!text.isEmpty()) {
                        appendWithSpace(textValue, text);
                    }
                }
            } else if (node instanceof TextNode textNode) {
                final String text = textNode.text().trim();
                if (!text.isEmpty()) {
                    appendWithSpace(textValue, text);
                }
            }
        }
        if (anchorValue != null) {
            return anchorValue;
        }
        final String text = textValue.toString().trim();
        if (!text.isEmpty()) {
            return text;
        }
        return contadorValue;
    }

    private static String stripOuterParens(final String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("(") && text.endsWith(")") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static boolean isEditAnchor(final Element element) {
        final String href = element.attr("href");
        if (href != null && href.contains("/editor/")) {
            return true;
        }
        return element.text().trim().isEmpty();
    }

    private static void appendWithSpace(final StringBuilder output, final String text) {
        if (output.length() > 0) {
            output.append(' ');
        }
        output.append(text);
    }

    private static String collectPlainText(final List<Node> buffer, final Element flagImg) {
        boolean afterFlag = false;
        final StringBuilder out = new StringBuilder();
        for (final Node node : buffer) {
            if (!afterFlag) {
                if (node == flagImg) {
                    afterFlag = true;
                }
                continue;
            }
            if (node instanceof TextNode textNode) {
                final String text = textNode.text().trim();
                if (!text.isEmpty()) {
                    appendWithSpace(out, text);
                }
            } else if (node instanceof Element element) {
                final String text = element.text().trim();
                if (!text.isEmpty()) {
                    appendWithSpace(out, text);
                }
            }
        }
        return out.toString().trim();
    }

    private static String extractTags(final Document doc) {
        final Elements anchors = doc.select("div.settags a");
        final StringJoiner tags = new StringJoiner(", ");
        final Set<String> seen = new LinkedHashSet<>();
        for (final Element anchor : anchors) {
            final String text = anchor.text().trim();
            if (!text.isEmpty() && seen.add(text.toLowerCase(Locale.ROOT))) {
                tags.add(text);
            }
        }
        return tags.length() == 0 ? null : tags.toString();
    }

    private static List<NormalizedAssetDto> extractImages(final Document doc) {
        final List<NormalizedAssetDto> assets = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        int sortOrder = 0;
        final Element mainAnchor = doc.selectFirst("div.thumb-wrap a[href]");
        if (mainAnchor != null) {
            sortOrder = addImage(assets, seen, mainAnchor, sortOrder);
        }
        for (final Element anchor : doc.select("a[rel='lightbox[set]'][href]")) {
            if (mainAnchor != null && anchor == mainAnchor) {
                continue;
            }
            sortOrder = addImage(assets, seen, anchor, sortOrder);
        }
        return assets;
    }

    private static int addImage(
        final List<NormalizedAssetDto> assets,
        final Set<String> seen,
        final Element anchor,
        final int sortOrder
    ) {
        final String href = anchor.attr("abs:href");
        if (href.isBlank() || !seen.add(href)) {
            return sortOrder;
        }
        if(href.startsWith("https://www.klickypedia.com/summary-sets-images")) {
            return sortOrder;
        }
        assets.add(new NormalizedAssetDto(href, AssetKind.IMAGE, labelFor(anchor), sortOrder));
        return sortOrder + 1;
    }

    private static String labelFor(final Element anchor) {
        final Element image = anchor.selectFirst("img");
        if (image != null) {
            final String title = image.attr("title").trim();
            if (!title.isEmpty()) {
                return title;
            }
            final String alt = image.attr("alt").trim();
            if (!alt.isEmpty()) {
                return alt;
            }
        }
        final String title = anchor.attr("title").trim();
        if (!title.isEmpty()) {
            return title;
        }
        return null;
    }

    private static LocalDate parseReleaseDate(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final Matcher full = FULL_DATE_PATTERN.matcher(value);
        if (full.find()) {
            try {
                return LocalDate.parse(full.group(0), ISO_DATE);
            } catch (Exception ignored) {
                // ignore and try year-only fallback
            }
        }
        final Matcher year = YEAR_PATTERN.matcher(value);
        if (year.find()) {
            try {
                return LocalDate.of(Integer.parseInt(year.group(1)), 1, 1);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(final String... values) {
        for (final String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

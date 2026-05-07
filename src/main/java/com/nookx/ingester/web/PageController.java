package com.nookx.ingester.web;

import com.nookx.ingester.domain.ScrapePage;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import com.nookx.ingester.repository.ScrapePageQueryRepository;
import com.nookx.ingester.repository.ScrapePageRepository;
import com.nookx.ingester.source.runtime.SourceRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class PageController {

    private static final int DEFAULT_LIMIT = 100;

    private final ScrapePageQueryRepository scrapePageQueryRepository;
    private final ScrapePageRepository scrapePageRepository;
    private final SourceRegistry sourceRegistry;

    @GetMapping("/pages")
    public String list(
        @RequestParam(name = "source", required = false) final String sourceCode,
        @RequestParam(name = "pageType", required = false) final String pageType,
        @RequestParam(name = "fetchStatus", required = false) final FetchStatus fetchStatus,
        @RequestParam(name = "parseStatus", required = false) final ParseStatus parseStatus,
        @RequestParam(name = "url", required = false) final String urlContains,
        @RequestParam(name = "limit", defaultValue = "100") final int limit,
        final Model model
    ) {
        final int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        final List<ScrapePage> pages = scrapePageQueryRepository.search(
            sourceCode,
            pageType,
            fetchStatus,
            parseStatus,
            urlContains,
            safeLimit
        );
        model.addAttribute("pages", pages);
        model.addAttribute("filterSource", sourceCode);
        model.addAttribute("filterPageType", pageType);
        model.addAttribute("filterFetchStatus", fetchStatus);
        model.addAttribute("filterParseStatus", parseStatus);
        model.addAttribute("filterUrl", urlContains);
        model.addAttribute("filterLimit", safeLimit);
        model.addAttribute("sourceCodes", sourceRegistry.indexedByCode().keySet());
        model.addAttribute("fetchStatuses", FetchStatus.values());
        model.addAttribute("parseStatuses", ParseStatus.values());
        model.addAttribute("pageTitle", "Pages");
        return "pages/list";
    }

    @PostMapping("/pages/{id}/re-fetch")
    @Transactional
    public String reFetch(@PathVariable("id") final Long id) {
        final Optional<ScrapePage> opt = scrapePageRepository.findById(id);
        opt.ifPresent(page -> {
            final Instant now = Instant.now();
            page.setFetchStatus(FetchStatus.PENDING);
            page.setFetchRetryCount(0);
            page.setFetchLastError(null);
            page.setEtag(null);
            page.setLastModified(null);
            page.setNextCheckAt(now);
            page.setUpdatedAt(now);
            scrapePageRepository.save(page);
        });
        return "redirect:/pages";
    }

    @PostMapping("/pages/{id}/re-parse")
    @Transactional
    public String reParse(@PathVariable("id") final Long id) {
        final Optional<ScrapePage> opt = scrapePageRepository.findById(id);
        opt.ifPresent(page -> {
            page.setParseStatus(ParseStatus.PENDING);
            page.setParseRetryCount(0);
            page.setParseLastError(null);
            page.setUpdatedAt(Instant.now());
            scrapePageRepository.save(page);
        });
        return "redirect:/pages";
    }
}

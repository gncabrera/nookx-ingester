package com.nookx.ingester.web;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.config.IngesterProperties.SourceConfig;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import com.nookx.ingester.pipeline.runner.JobDispatcher;
import com.nookx.ingester.pipeline.service.ScrapePageService;
import com.nookx.ingester.repository.ScrapePageRepository;
import com.nookx.ingester.source.api.Source;
import com.nookx.ingester.source.runtime.SourceRegistry;
import com.nookx.ingester.web.dto.SourceView;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class SourceController {

    private final SourceRegistry sourceRegistry;
    private final ScrapePageRepository scrapePageRepository;
    private final ScrapePageService scrapePageService;
    private final JobDispatcher jobDispatcher;
    private final IngesterProperties properties;

    @GetMapping("/sources")
    public String list(final Model model) {
        model.addAttribute("sources", buildViews());
        model.addAttribute("pageTitle", "Sources");
        return "sources/list";
    }

    @PostMapping("/sources/{code}/discovery/run")
    public String runDiscovery(@PathVariable("code") final String code, final Model model) {
        jobDispatcher.dispatchDiscoveryForSource(code);
        return refreshFragment(code, model, "Discovery dispatched for " + code);
    }

    @PostMapping("/sources/{code}/crawl/run")
    public String runCrawl(
        @PathVariable("code") final String code,
        @RequestParam(name = "limit", defaultValue = "200") final int limit,
        @RequestParam(name = "force", defaultValue = "false") final boolean force,
        final Model model
    ) {
        jobDispatcher.dispatchCrawlForSource(code, limit, force);
        return refreshFragment(code, model, "Crawl dispatched for " + code);
    }

    @PostMapping("/sources/{code}/errors/reset")
    public String resetErrors(
        @PathVariable("code") final String code,
        @RequestParam("stage") final String stage,
        final Model model
    ) {
        final int affected = "parse".equalsIgnoreCase(stage)
            ? scrapePageService.resetParseErrorsBySource(code)
            : scrapePageService.resetFetchErrorsBySource(code);
        return refreshFragment(code, model, "Reset " + stage + " errors: " + affected + " row(s)");
    }

    private String refreshFragment(final String code, final Model model, final String message) {
        model.addAttribute("source", findView(code));
        model.addAttribute("flashMessage", message);
        return "sources/_row :: row(${source}, ${flashMessage})";
    }

    private SourceView findView(final String code) {
        for (final SourceView view : buildViews()) {
            if (view.code().equals(code)) {
                return view;
            }
        }
        return null;
    }

    private List<SourceView> buildViews() {
        final List<SourceView> views = new ArrayList<>();
        for (final Source source : sourceRegistry.all()) {
            final SourceConfig config = properties.getSources().get(source.code());
            views.add(SourceView.builder()
                .withCode(source.code())
                .withIngestTargetCode(source.ingestTargetCode())
                .withEnabled(config == null || config.isEnabled())
                .withDiscoveryCron(config != null ? config.getDiscoveryCron() : null)
                .withPendingFetch(scrapePageRepository.countBySourceCodeAndFetchStatus(source.code(), FetchStatus.PENDING))
                .withFailedFetch(scrapePageRepository.countBySourceCodeAndFetchStatus(source.code(), FetchStatus.FAILED))
                .withPendingParse(scrapePageRepository.countBySourceCodeAndParseStatus(source.code(), ParseStatus.PENDING))
                .withFailedParse(scrapePageRepository.countBySourceCodeAndParseStatus(source.code(), ParseStatus.FAILED))
                .build());
        }
        return views;
    }
}

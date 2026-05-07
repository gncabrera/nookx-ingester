package com.nookx.ingester.web;

import com.nookx.ingester.config.IngesterProperties;
import com.nookx.ingester.domain.JobRun;
import com.nookx.ingester.domain.enumeration.FetchStatus;
import com.nookx.ingester.domain.enumeration.JobStatus;
import com.nookx.ingester.domain.enumeration.ParseStatus;
import com.nookx.ingester.domain.enumeration.PushStatus;
import com.nookx.ingester.job.JobRunService;
import com.nookx.ingester.repository.ParsedPayloadRepository;
import com.nookx.ingester.repository.ScrapePageRepository;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class OverviewController {

    private static final Duration LAST_24H = Duration.ofHours(24);

    private final JobRunService jobRunService;
    private final ScrapePageRepository scrapePageRepository;
    private final ParsedPayloadRepository parsedPayloadRepository;
    private final IngesterProperties properties;

    @GetMapping("/")
    public String overview(final Model model) {
        addCommon(model);
        return "overview";
    }

    @GetMapping("/fragments/overview")
    public String overviewFragment(final Model model) {
        addCommon(model);
        return "overview :: stats";
    }

    private void addCommon(final Model model) {
        final List<JobRun> running = jobRunService.running();
        final List<JobRun> recent = jobRunService.recent(20);
        model.addAttribute("running", running);
        model.addAttribute("recent", recent);
        model.addAttribute("successLast24h", jobRunService.countSinceByStatus(JobStatus.SUCCESS, LAST_24H));
        model.addAttribute("failedLast24h", jobRunService.countSinceByStatus(JobStatus.FAILED, LAST_24H));
        model.addAttribute("pendingFetch", scrapePageRepository.countByFetchStatus(FetchStatus.PENDING));
        model.addAttribute("failedFetch", scrapePageRepository.countByFetchStatus(FetchStatus.FAILED));
        model.addAttribute("pendingParse", scrapePageRepository.countByParseStatus(ParseStatus.PENDING));
        model.addAttribute("failedParse", scrapePageRepository.countByParseStatus(ParseStatus.FAILED));
        model.addAttribute("pendingPush", parsedPayloadRepository.countByPushStatus(PushStatus.PENDING));
        model.addAttribute("failedPush", parsedPayloadRepository.countByPushStatus(PushStatus.FAILED));
        model.addAttribute("pollIntervalMs", properties.getDashboard().getPollIntervalMs());
        model.addAttribute("pageTitle", "Overview");
    }
}

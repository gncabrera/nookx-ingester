package com.nookx.ingester.web;

import com.nookx.ingester.config.IngesterProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Slf4j
@Controller
@RequiredArgsConstructor
public class LogController {

    private static final int MAX_BYTES = 256 * 1024;

    private final IngesterProperties properties;

    @Value("${logging.file.name:logs/nookx-ingester.log}")
    private String logFilePath;

    @GetMapping("/logs")
    public String logsPage(final Model model) {
        final int lines = properties.getDashboard().getLogTailLines();
        model.addAttribute("logFile", logFilePath);
        model.addAttribute("logContent", tail(logFilePath, lines));
        model.addAttribute("pollIntervalMs", properties.getDashboard().getPollIntervalMs());
        model.addAttribute("pageTitle", "Logs");
        return "logs/page";
    }

    @GetMapping("/logs/tail")
    public String logsTail(final Model model) {
        final int lines = properties.getDashboard().getLogTailLines();
        final String content = tail(logFilePath, lines);
        model.addAttribute("logContent", content);
        model.addAttribute("pollIntervalMs", properties.getDashboard().getPollIntervalMs());
        return "logs/page :: tail";
    }

    private static String tail(final String filePath, final int maxLines) {
        final Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            return "(log file not found: " + filePath + ")";
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            final long fileLength = file.length();
            final long readFrom = Math.max(0, fileLength - MAX_BYTES);
            file.seek(readFrom);
            final byte[] buffer = new byte[(int) (fileLength - readFrom)];
            file.readFully(buffer);
            final String text = new String(buffer, StandardCharsets.UTF_8);
            return lastLines(text, maxLines);
        } catch (IOException ex) {
            log.warn("[Ingester/LogController] - TAIL: failed to read log file: {}", ex.getMessage());
            return "(error reading log file: " + ex.getMessage() + ")";
        }
    }

    private static String lastLines(final String text, final int maxLines) {
        final String[] lines = text.split("\\R");
        final Deque<String> deque = new ArrayDeque<>(maxLines);
        for (int i = Math.max(0, lines.length - maxLines); i < lines.length; i++) {
            deque.addLast(lines[i]);
        }
        final StringBuilder out = new StringBuilder();
        for (final String line : deque) {
            out.append(line).append('\n');
        }
        return out.toString();
    }
}

package com.nookx.ingester.source.api;

import com.nookx.ingester.source.api.dto.DiscoveredUrl;
import java.util.List;

public interface SourceDiscoverer {

    List<DiscoveredUrl> discover();
}

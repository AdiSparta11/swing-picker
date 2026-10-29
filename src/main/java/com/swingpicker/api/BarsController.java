package com.swingpicker.api;

import com.swingpicker.core.MarketDataProvider;
import com.swingpicker.domain.Bar;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** GET /api/bars/RELIANCE?years=1 : proves market data reaches this server, and shows what the setups will see. */
@RestController
@RequestMapping("/api")
public class BarsController {

    public record BarsSummary(String symbol, int count, LocalDate first, LocalDate last, List<Bar> latest) {
    }

    private final MarketDataProvider data;

    public BarsController(MarketDataProvider data) {
        this.data = data;
    }

    @GetMapping("/bars/{symbol}")
    public BarsSummary bars(@PathVariable String symbol, @RequestParam(defaultValue = "1") int years) {
        List<Bar> bars = data.dailyBars(symbol, Math.max(1, Math.min(years, 10)));
        if (bars.isEmpty()) {
            return new BarsSummary(symbol, 0, null, null, List.of());
        }
        return new BarsSummary(symbol, bars.size(), bars.get(0).date(), bars.get(bars.size() - 1).date(),
                bars.subList(Math.max(0, bars.size() - 5), bars.size()));
    }
}

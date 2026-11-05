package com.swingpicker.api;

import com.swingpicker.core.analysis.StockAnalysis;
import com.swingpicker.core.analysis.StockAnalysisService;
import com.swingpicker.core.analysis.StockAnalysisService.Position;
import com.swingpicker.data.SymbolSearch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * GET /api/search?q=kaynes                   -> matching NSE symbols
 * GET /api/stock?symbol=KAYNES               -> prospects of a stock you're thinking of buying
 * GET /api/stock?symbol=KAYNES&qty=29&avgPrice=4230.52&buyDate=2025-03-10 -> same, plus your P&L and tax notes
 */
@RestController
@RequestMapping("/api")
public class StockController {

    private final StockAnalysisService analysis;
    private final SymbolSearch search;

    public StockController(StockAnalysisService analysis, SymbolSearch search) {
        this.analysis = analysis;
        this.search = search;
    }

    @GetMapping("/search")
    public List<SymbolSearch.Match> search(@RequestParam("q") String q) {
        return search.search(q);
    }

    @GetMapping("/stock")
    public StockAnalysis stock(@RequestParam("symbol") String symbol,
                               @RequestParam(value = "qty", required = false) Integer qty,
                               @RequestParam(value = "avgPrice", required = false) Double avgPrice,
                               @RequestParam(value = "buyDate", required = false) String buyDate) {
        Position position = null;
        if (qty != null || avgPrice != null) {
            if (qty == null || qty <= 0 || avgPrice == null || avgPrice <= 0) {
                throw new IllegalArgumentException("For a holding, give both quantity and average price (both above 0)");
            }
            LocalDate date = null;
            if (buyDate != null && !buyDate.isBlank()) {
                try {
                    date = LocalDate.parse(buyDate.trim());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Buy date should look like 2025-03-10");
                }
                if (date.isAfter(LocalDate.now())) {
                    throw new IllegalArgumentException("Buy date is in the future");
                }
            }
            position = new Position(qty, avgPrice, date);
        }
        return analysis.analyze(symbol, position);
    }
}

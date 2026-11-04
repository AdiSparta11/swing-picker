package com.swingpicker.data;

import com.swingpicker.core.RecommendationService.Stock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniverseLoaderTest {

    @Test
    void parsesNseConstituentCsvKeepingEqSeriesOnly() {
        String csv = """
            Company Name,Industry,Symbol,Series,ISIN Code\r
            Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\r
            "Mahindra & Mahindra, Ltd.",Automobile and Auto Components,M&M,EQ,INE101A01026\r
            Some Partly Paid Ltd.,Financial Services,XYZPP,E1,INE000000000\r
            """;

        List<Stock> stocks = UniverseLoader.parseNseConstituents(csv);

        assertEquals(List.of(
                new Stock("RELIANCE", "Oil Gas & Consumable Fuels"),
                new Stock("M&M", "Automobile and Auto Components")), stocks);
    }

    @Test
    void bundledNifty200FileIsCompleteAndParses() throws Exception {
        String csv = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/nifty200.csv"));

        List<Stock> stocks = UniverseLoader.parseNseConstituents(csv);

        assertEquals(200, stocks.size());
        assertEquals(200, stocks.stream().map(Stock::symbol).distinct().count());
        assertEquals(true, stocks.contains(new Stock("M&M", "Automobile and Auto Components")));
        assertEquals(true, stocks.contains(new Stock("RELIANCE", "Oil Gas & Consumable Fuels")));
        assertEquals(true, stocks.stream().noneMatch(s -> s.symbol().isBlank() || s.sector().isBlank()));
    }

    @Test
    void parsesBundledList() {
        assertEquals(List.of(new Stock("TCS", "IT"), new Stock("ITC", "FMCG")),
                UniverseLoader.parseBundled("symbol,sector\nTCS,IT\nITC,FMCG\n\n"));
    }
}

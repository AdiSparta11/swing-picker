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
    void parsesBundledList() {
        assertEquals(List.of(new Stock("TCS", "IT"), new Stock("ITC", "FMCG")),
                UniverseLoader.parseBundled("symbol,sector\nTCS,IT\nITC,FMCG\n\n"));
    }
}

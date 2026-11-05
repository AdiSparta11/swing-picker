package com.swingpicker.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolSearchTest {

    @Test
    void companyNamesComeFromTheBundledNseFile() throws Exception {
        Map<String, String> names = UniverseLoader.companyNames(Files.readString(Path.of("src/main/resources/nifty200.csv")));

        assertEquals(200, names.size());
        assertEquals("Aurobindo Pharma Ltd.", names.get("AUROPHARMA"));
        assertEquals("Mahindra & Mahindra Ltd.", names.get("M&M"));
    }

    @Test
    void localSearchMatchesSymbolsAndNamesExactFirst() {
        SymbolSearch s = new SymbolSearch(Map.of(
                "TCS", "Tata Consultancy Services Ltd.",
                "TATASTEEL", "Tata Steel Ltd.",
                "INFY", "Infosys Ltd."), null);

        List<SymbolSearch.Match> tata = s.search("tata");
        assertEquals(2, tata.size());
        assertEquals("TCS", s.search("tcs").get(0).symbol());
        assertEquals("INFY", s.search("infosys").get(0).symbol());
        assertTrue(s.search("x").isEmpty());
    }

    @Test
    void yahooResultsKeepOnlyNseEquities() {
        String json = """
            {"quotes":[
              {"symbol":"KAYNES.NS","quoteType":"EQUITY","exchange":"NSI","shortname":"KAYNES TECHNOLOGY","longname":"Kaynes Technology India Limited"},
              {"symbol":"KAYNES.BO","quoteType":"EQUITY","exchange":"BSE","shortname":"KAYNES TECHNOLOGY"},
              {"symbol":"^NSEI","quoteType":"INDEX","exchange":"NSI","shortname":"NIFTY 50"},
              {"symbol":"KPIGREEN.NS","quoteType":"EQUITY","exchange":"NSI","shortname":"KPI GREEN ENERGY"}
            ]}
            """;

        List<SymbolSearch.Match> m = SymbolSearch.parseYahoo(json);

        assertEquals(List.of("KAYNES", "KPIGREEN"), m.stream().map(SymbolSearch.Match::symbol).toList());
        assertEquals("Kaynes Technology India Limited", m.get(0).name());
        assertEquals("KPI GREEN ENERGY", m.get(1).name());
    }
}

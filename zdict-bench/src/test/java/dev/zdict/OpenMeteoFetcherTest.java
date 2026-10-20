package dev.zdict;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenMeteoFetcherTest {

    @Test
    void testConstantsAndCityList() {
        assertEquals(300, OpenMeteoFetcher.FORECAST_COUNT);
        assertEquals(200, OpenMeteoFetcher.GEOCODING_COUNT);
        assertEquals(200, OpenMeteoFetcher.DEFAULT_DELAY_MS);
        assertEquals(2, OpenMeteoFetcher.MAX_RETRIES);
        assertTrue(OpenMeteoFetcher.USER_AGENT.contains("zdict-bench"));
        assertEquals(200, OpenMeteoFetcher.CITIES.size());

        // Verify city names are non-blank and unique
        long distinctCities = OpenMeteoFetcher.CITIES.stream()
                .map(String::trim)
                .distinct()
                .count();
        assertEquals(200, distinctCities);
    }

    @Test
    void testCoordinateGenerationAcrossIndia() {
        for (int i = 0; i < 300; i++) {
            OpenMeteoFetcher.Coordinate coord = OpenMeteoFetcher.generateIndiaCoordinate(i, 300);
            assertTrue(coord.latitude >= 8.5 && coord.latitude <= 34.5,
                    "Latitude out of bounds for index " + i + ": " + coord.latitude);
            assertTrue(coord.longitude >= 69.0 && coord.longitude <= 95.0,
                    "Longitude out of bounds for index " + i + ": " + coord.longitude);
        }

        // Check that points vary across the range
        OpenMeteoFetcher.Coordinate c0 = OpenMeteoFetcher.generateIndiaCoordinate(0, 300);
        OpenMeteoFetcher.Coordinate cLast = OpenMeteoFetcher.generateIndiaCoordinate(299, 300);
        assertNotEquals(c0.latitude, cLast.latitude);
        assertNotEquals(c0.longitude, cLast.longitude);
    }
}

package org.almond_eye_bot;

import com.alibaba.fastjson2.JSON;
import org.almond_eye_bot.client.WowsStatsClient;
import org.almond_eye_bot.config.AdminAccessFilter;
import org.almond_eye_bot.controller.WowsStatsController;
import org.almond_eye_bot.service.*;
import org.almond_eye_bot.service.WowsStatsData.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class WowsStatsApiTests {
    @TempDir Path directory;
    WowsStatsRepository repository;
    WowsStatsClient client;
    WowsStatsService service;
    MockMvc api;
    Instant now = Instant.parse("2026-10-24T03:00:00Z");
    @BeforeEach void setup() throws Exception {
        repository = new WowsStatsRepository(directory.toString());
        client = mock(WowsStatsClient.class);
        when(client.isConfigured()).thenReturn(true);
        service = new WowsStatsService(client, repository, Clock.fixed(now, ZoneOffset.UTC));
        api = MockMvcBuilders.standaloneSetup(new WowsStatsController(service))
                .addFilters(new AdminAccessFilter("test-token")).build();
    }
    @AfterEach void close() throws Exception { repository.close(); }
    Player player(Region region, long battles) {
        return new Player(region, 123, "Test_Player", battles + 10,
                new Totals(battles, battles / 2, battles * 50000, battles, battles / 4, battles * 1000), now.getEpochSecond());
    }
    MockHttpServletRequestBuilder request(String mode, String body) {
        String path = "/admin/api/wows/" + mode;
        return post(path).servletPath(path).contentType("application/json").content(body)
                .header("Authorization", "Bearer test-token").header("X-Requested-With", "AlmondAdmin");
    }
    @ParameterizedTest @EnumSource(Region.class)
    void overallReturnsStructuredRandomStatsAndSharesCacheWithBot(Region region) throws Exception {
        when(client.player(region, "123")).thenReturn(player(region, 100));
        var response = api.perform(request("overall", "{\"region\":\"" + region + "\",\"player\":\"123\"}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var data = JSON.parseObject(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("OK", data.getString("status"));
        assertEquals("PVP", data.getString("battleType"));
        assertEquals(110, data.getJSONObject("player").getLongValue("allBattles"));
        assertEquals(100, data.getJSONObject("totals").getLongValue("battles"));
        assertEquals(50.0, data.getJSONObject("metrics").getDoubleValue("winRate"));
        assertEquals(now.toEpochMilli(), data.getLongValue("observedTo"));
        assertTrue(service.query(region + " 123 总体").contains("随机战：100 场"));
        verify(client).player(region, "123");
    }
    @Test void recentUsesOnlyMatchingBaselineAndReturnsActualWindow() throws Exception {
        long start = now.minus(Duration.ofDays(7)).minusSeconds(1800).toEpochMilli();
        repository.save(player(Region.ASIA, 100), start, true);
        when(client.player(Region.ASIA, "123")).thenReturn(player(Region.ASIA, 120));
        var response = api.perform(request("recent", "{\"region\":\"ASIA\",\"player\":\"123\",\"days\":7}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var data = JSON.parseObject(response.getContentAsString());
        assertEquals(20, data.getJSONObject("totals").getLongValue("battles"));
        assertEquals(50000, data.getJSONObject("metrics").getDoubleValue("averageDamage"));
        assertEquals(start, data.getLongValue("observedFrom"));
        assertEquals(now.minus(Duration.ofDays(7)).toEpochMilli(), data.getLongValue("requestedFrom"));
    }
    @Test void missingHistoryIsConflictWithNullTotalsButStillStartsTracking() throws Exception {
        when(client.player(Region.EU, "123")).thenReturn(player(Region.EU, 100));
        var response = api.perform(request("recent", "{\"region\":\"EU\",\"player\":\"123\",\"days\":7}"))
                .andReturn().getResponse();
        assertEquals(409, response.getStatus());
        var data = JSON.parseObject(response.getContentAsString());
        assertEquals("HISTORY_UNAVAILABLE", data.getString("status"));
        assertNull(data.get("totals")); assertNull(data.get("metrics"));
        assertEquals(100, repository.baseline(Region.EU, 123, now.toEpochMilli()).orElseThrow().random().battles());
    }
    @Test void rejectsInvalidBodiesBeforeAnyUpstreamCall() throws Exception {
        for (String body : new String[]{"{}", "null", "{\"region\":\"EU\",\"player\":\"123\",\"days\":0}",
                "{\"region\":\"CN\",\"player\":\"123\",\"days\":7}",
                "{\"region\":\"EU\",\"player\":\"123 总体\",\"days\":7}",
                "{\"region\":\"EU\",\"player\":\"123\",\"days\":366}"})
            assertEquals(400, api.perform(request("recent", body)).andReturn().getResponse().getStatus());
        assertEquals(400, api.perform(request("overall", "{\"region\":\"EU\",\"player\":\"123\",\"days\":7}"))
                .andReturn().getResponse().getStatus());
        verify(client, never()).player(any(), anyString());
    }
    @Test void existingAuthAndPostHeaderProtectNewEndpoints() throws Exception {
        String path = "/admin/api/wows/overall";
        assertEquals(401, api.perform(post(path).servletPath(path).contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus());
        assertEquals(403, api.perform(post(path).servletPath(path).contentType("application/json").content("{}")
                .header("Authorization", "Bearer test-token")).andReturn().getResponse().getStatus());
        verifyNoInteractions(client);
    }
    @Test void exposesRateLimitDisabledConfigurationAndCounterResetSeparately() throws Exception {
        when(client.player(Region.EU, "123")).thenReturn(player(Region.EU, 100));
        assertEquals(Status.OK, service.queryData("EU 123 总体").status());
        assertEquals(429, api.perform(request("overall", "{\"region\":\"EU\",\"player\":\"456\"}"))
                .andReturn().getResponse().getStatus());
        repository.save(player(Region.EU, 200), now.minus(Duration.ofDays(1)).toEpochMilli(), true);
        assertEquals(Status.COUNTER_RESET, service.queryData("EU 123 近期 1").status());
        ReflectionTestUtils.setField(service, "enabled", false);
        assertEquals(Status.DISABLED, service.queryData("EU 123 总体").status());
        ReflectionTestUtils.setField(service, "enabled", true);
        when(client.isConfigured()).thenReturn(false);
        assertEquals(503, api.perform(request("overall", "{\"region\":\"EU\",\"player\":\"123\"}"))
                .andReturn().getResponse().getStatus());
    }
    @Test void zeroBattlesAndZeroDeathsUseNullInsteadOfInventedRatios() {
        assertNull(Metrics.of(new Totals(0, 0, 0, 0, 0, 0)).winRate());
        var metrics = Metrics.of(new Totals(10, 6, 10000, 5, 10, 1000));
        assertEquals(60.0, metrics.winRate()); assertNull(metrics.killDeathRatio());
    }

    @Test void disabledSpringDefaultsDoNotOpenDatabaseOrCallUpstream() throws Exception {
        Path disabledStorage = directory.resolve("disabled-feature");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "test-storage", java.util.Map.of("bot.storage-directory", disabledStorage.toString())));
            context.registerBean(WowsStatsClient.class, () -> client);
            context.register(WowsStatsRepository.class, WowsStatsService.class);
            context.refresh();
            clearInvocations(client); // Exclude Spring's configuration-setter injection during bean initialization.
            var disabled = context.getBean(WowsStatsService.class);
            assertEquals(Status.DISABLED, disabled.queryData("EU 123 总体").status());
            assertFalse(disabled.queryResult("EU 123 总体").success());
            disabled.refresh();
            var controller = new WowsStatsController(disabled);
            assertEquals(503, controller.overall(new WowsStatsController.Input("EU", "123", null)).getStatusCode().value());
            assertFalse(java.nio.file.Files.exists(disabledStorage));
            verifyNoInteractions(client);
        }
    }
}

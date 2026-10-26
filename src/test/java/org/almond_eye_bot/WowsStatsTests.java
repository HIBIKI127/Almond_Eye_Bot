package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.WowsStatsClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.service.*;
import org.almond_eye_bot.service.WowsStatsData.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WowsStatsTests {
    @TempDir Path directory;
    WowsStatsRepository repo;
    WowsStatsClient client;
    MockRestServiceServer server;
    Instant now = Instant.parse("2026-10-23T14:00:00Z");
    @BeforeEach void setup() throws Exception {
        repo = new WowsStatsRepository(directory.toString());
        client = new WowsStatsClient();
        ReflectionTestUtils.setField(client,"applicationId","mock-app");
        server = MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"restTemplate")).build();
    }
    @AfterEach void close() throws Exception { repo.close(); }
    Totals totals(long n) { return new Totals(n,n/2,n*50000,n,n/4,n*1000); }
    Player player(Region region, long id, long n) { return new Player(region,id,"Test_Player",n+10,totals(n),now.getEpochSecond()); }
    String payload(long id, long n) {
        return JSONObject.of("status","ok","data",Map.of(Long.toString(id),JSONObject.of(
                "account_id",id,"nickname","Test_Player","hidden_profile",false,"updated_at",now.getEpochSecond(),
                "statistics",JSONObject.of("battles",n+10,"pvp",JSONObject.of("battles",n,"wins",n/2,
                        "damage_dealt",n*50000,"frags",n,"survived_battles",n/4,"xp",n*1000))))).toJSONString();
    }

    @Test void commandAcceptsThreeRegionsNicknameIdAndBothModes() {
        assertEquals(Region.ASIA,Command.parse("亚服 200000001 总体").region());
        assertEquals(Region.EU,Command.parse("EU Test_Player 近期 7天").region());
        assertEquals(365,Command.parse("na Test_Player 近期 365").days());
    }
    @ParameterizedTest @ValueSource(strings={"cn abc 总体","EU ab 总体","EU abc 近期 0","EU abc 近期 366",
            "EU abc 近期 -1","EU abc 近期 7 多余","EU https://evil 总体","EU abc 总体 7","EU abc 最近 7"})
    void malformedCommandsNeverCallNetwork(String input) {
        var service = new WowsStatsService(client,repo,Clock.fixed(now,ZoneOffset.UTC));
        assertFalse(service.query(input).isBlank()); server.verify();
    }

    @ParameterizedTest @ValueSource(strings={"ASIA","EU","NA"})
    void officialEndpointsSeparateRealmsAndUseRandomStats(String name) {
        Region region=Region.valueOf(name);
        server.expect(request -> {
            assertEquals("api.worldofwarships."+region.apiDomain,request.getURI().getHost());
            assertEquals("/wows/account/info/",request.getURI().getPath());
            String query=URLDecoder.decode(request.getURI().getRawQuery(),StandardCharsets.UTF_8);
            assertTrue(query.contains("application_id=mock-app")); assertTrue(query.contains("statistics.pvp.battles"));
        }).andRespond(withSuccess(payload(123,100),MediaType.APPLICATION_JSON));
        var p=client.player(region,"123");
        assertEquals(100,p.random().battles()); assertEquals(110,p.allBattles()); server.verify();
    }

    @Test void resolvesExactNicknameAndDoesNotPickDifferentAccount() {
        server.expect(request -> {
            assertTrue(request.getURI().getPath().endsWith("account/list/"));
            assertTrue(request.getURI().getQuery().contains("type=exact"));
        }).andRespond(withSuccess("{\"status\":\"ok\",\"data\":[{\"account_id\":123,\"nickname\":\"Test_Player\"}]}",MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(payload(123,100),MediaType.APPLICATION_JSON));
        assertEquals(123,client.player(Region.EU,"test_player").id()); server.verify();
    }

    @ParameterizedTest @ValueSource(strings={
            "{\"status\":\"ok\",\"data\":{\"123\":null}}",
            "{\"status\":\"ok\",\"data\":{\"123\":{\"hidden_profile\":true}}}",
            "{\"status\":\"error\",\"error\":{\"code\":407,\"message\":\"mock-app-secret\"}}",
            "<html>challenge</html>"})
    void hiddenMissingAndMalformedResponsesAreNotTurnedIntoStats(String body) {
        server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var error=assertThrows(IllegalArgumentException.class,()->client.player(Region.ASIA,"123"));
        assertFalse(error.getMessage().contains("mock-app-secret")); server.verify();
    }

    @Test void overallContainsOnlyVerifiedMetricsAndSmallSamplesAreNotRatedAsSkill() {
        var mock=mock(WowsStatsClient.class); when(mock.isConfigured()).thenReturn(true);
        when(mock.player(Region.EU,"123")).thenReturn(player(Region.EU,123,100));
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        String report=service.query("EU 123 总体");
        assertTrue(report.contains("所有模式总场次：110")); assertTrue(report.contains("累计随机战"));
        assertTrue(report.contains("50.00%")); assertTrue(report.contains("50000")); assertTrue(report.contains("锐评"));
        assertTrue(report.contains("wows-numbers.com/player/123")); assertFalse(report.contains("PR："));
        assertEquals(report,service.query("EU 123 总体")); verify(mock).player(Region.EU,"123");
        assertTrue(WowsStatsServiceTestAccess.roast(totals(2)).contains("样本"));
    }

    @Test void recentComputesDifferencesAndLabelsActualPeriodAndSource() {
        repo.save(player(Region.ASIA,123,100),now.minus(Duration.ofDays(7)).minusSeconds(1800).toEpochMilli(),true);
        var mock=mock(WowsStatsClient.class); when(mock.isConfigured()).thenReturn(true);
        when(mock.player(Region.ASIA,"123")).thenReturn(player(Region.ASIA,123,120));
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        String report=service.query("亚服 123 近期 7");
        assertTrue(report.contains("随机战：20 场")); assertTrue(report.contains("50.00%"));
        assertTrue(report.contains("50000")); assertTrue(report.contains("实际采集区间")); assertTrue(report.contains("非逐局日志"));
    }

    @Test void missingOldOrWrongRealmBaselineNeverPretendsToBeRecentStats() {
        repo.save(player(Region.EU,123,100),now.minus(Duration.ofDays(7)).toEpochMilli(),true);
        repo.save(player(Region.ASIA,123,100),now.minus(Duration.ofDays(8)).toEpochMilli(),true);
        var mock=mock(WowsStatsClient.class); when(mock.isConfigured()).thenReturn(true);
        when(mock.player(Region.ASIA,"123")).thenReturn(player(Region.ASIA,123,120));
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        var report=service.query("亚服 123 近期 7");
        assertTrue(report.contains("历史快照不足")); assertFalse(report.contains("50.00%"));
        assertTrue(report.contains("不做战绩锐评"));
    }

    @Test void counterRollbackAndZeroBattlesNeverProduceNegativeStatsOrDivisionByZero() {
        repo.save(player(Region.ASIA,123,200),now.minus(Duration.ofDays(1)).toEpochMilli(),true);
        var mock=mock(WowsStatsClient.class); when(mock.isConfigured()).thenReturn(true);
        when(mock.player(Region.ASIA,"123")).thenReturn(player(Region.ASIA,123,100));
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        assertTrue(service.query("亚服 123 近期 1").contains("回退"));
        assertEquals(0,totals(0).winRate());
        assertThrows(IllegalArgumentException.class,()->new Totals(10,11,0,0,0,0));
    }

    @Test void snapshotsPersistAcrossRestartAndSchedulerDoesNotExtendUserTrackingLifetime() throws Exception {
        repo.save(player(Region.EU,123,100),now.toEpochMilli(),true);
        repo.close(); repo=new WowsStatsRepository(directory.toString());
        assertEquals(100,repo.baseline(Region.EU,123,now.toEpochMilli()).orElseThrow().random().battles());
        assertEquals(1,repo.due(now.plusSeconds(3601).toEpochMilli()).size());
        repo.save(player(Region.EU,123,120),now.plusSeconds(3601).toEpochMilli(),false);
        assertTrue(repo.due(now.plusSeconds(3602).toEpochMilli()).isEmpty());
        assertTrue(repo.due(now.plus(Duration.ofDays(31)).toEpochMilli()).isEmpty());
    }

    @Test void enabledAndApplicationIdGuardsAndRefreshRespectFeatureSwitch() {
        var mock=mock(WowsStatsClient.class);
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        assertTrue(service.query("EU abc 总体").contains("APPLICATION_ID")); verify(mock,never()).player(any(),anyString());
        ReflectionTestUtils.setField(service,"enabled",false);
        assertTrue(service.query("EU abc 总体").contains("关闭")); service.refresh();
        verify(mock,never()).player(any(),anyString());
    }

    @Test void backgroundRefreshUpdatesPublicSnapshotAndBacksOffFailuresWithoutFakeData() {
        repo.save(player(Region.EU,123,100),now.minusSeconds(3601).toEpochMilli(),true);
        repo.save(player(Region.ASIA,456,50),now.minusSeconds(3601).toEpochMilli(),true);
        var mock=mock(WowsStatsClient.class); when(mock.isConfigured()).thenReturn(true);
        when(mock.player(Region.EU,"123")).thenReturn(player(Region.EU,123,120));
        when(mock.player(Region.ASIA,"456")).thenThrow(new IllegalArgumentException("模拟API失败"));
        var service=new WowsStatsService(mock,repo,Clock.fixed(now,ZoneOffset.UTC));
        service.refresh();
        assertEquals(120,repo.baseline(Region.EU,123,now.toEpochMilli()).orElseThrow().random().battles());
        assertEquals(50,repo.baseline(Region.ASIA,456,now.toEpochMilli()).orElseThrow().random().battles());
        assertTrue(repo.due(now.plusSeconds(1).toEpochMilli()).isEmpty());
    }

    @Test void sameHourSnapshotDoesNotDoubleCountAndTrackedAccountsStayBounded() {
        for (int i=1;i<=102;i++) repo.save(player(Region.EU,i,100),now.toEpochMilli()+i,true);
        assertTrue(repo.baseline(Region.EU,1,now.toEpochMilli()+1000).isEmpty());
        repo.save(player(Region.EU,102,120),now.plusSeconds(10).toEpochMilli(),true);
        assertEquals(120,repo.baseline(Region.EU,102,now.plusSeconds(10).toEpochMilli()).orElseThrow().random().battles());
        assertEquals(20,repo.due(now.plusSeconds(3601).toEpochMilli()).size());
    }

    @Test void ruleMigrationAndOnebotRoutingRespectGroupGateAndSkipAi() throws Exception {
        var store=new TagImageTests.Store(directory.resolve("config"));
        try {
            var rule=store.match("战绩查询 欧服 Test_Player 近期 7").orElseThrow();
            assertEquals("WOWS_STATS",rule.action()); assertEquals("欧服 Test_Player 近期 7",rule.argument());
            var ai=mock(AiChatService.class); var groups=mock(GroupAutoReplyService.class); var stats=mock(WowsStatsService.class);
            when(stats.queryResult(anyString())).thenReturn(new WowsStatsService.Result("随机战100场", "别急着当元帅。", true));
            var replies=new BotReplyService(ai,mock(LocalImageService.class),mock(WikiService.class),store,groups);
            ReflectionTestUtils.setField(replies,"wowsStats",stats);
            var onebot=new OneBotMessageService(store,replies,groups);
            var event=JSONObject.of("self_id",100,"user_id",200,"group_id",300,"message_type","group","message","战绩查询 欧服 Test_Player 总体");
            List<Map<String,Object>> sent=new ArrayList<>();
            onebot.handleEvent(event,(action,params)->sent.add(params)); verifyNoInteractions(stats);
            store.saveGroup(GroupAutoReplyTests.policy("300","ALL",10,1));
            store.saveSettings(new Settings(false,false,false,false,false));
            onebot.handleEvent(event,(action,params)->sent.add(params));
            assertFalse(sent.isEmpty()); verify(stats).queryResult("欧服 Test_Player 总体"); verifyNoInteractions(ai,groups);
            var r=store.rules().stream().filter(item->item.action().equals("WOWS_STATS")).findFirst().orElseThrow();
            store.deleteRule(r.id());
        } finally { store.close(); }
        var reopened=new TagImageTests.Store(directory.resolve("config"));
        try { assertTrue(reopened.match("战绩查询 EU abc 总体").isEmpty()); } finally { reopened.close(); }
    }

    // Invoke the package-private deterministic commentary without introducing a production API.
    static final class WowsStatsServiceTestAccess {
        static String roast(Totals t) { return ReflectionTestUtils.invokeMethod(WowsStatsService.class,"roast",t); }
    }
}

package org.almond_eye_bot;

import com.alibaba.fastjson2.*;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalShipDataTests {
    @TempDir Path root;
    BotConfigurationService store;
    @BeforeEach void setup() throws Exception { store = new BotConfigurationService(root.toString(), new OneBotProperties()); }
    @AfterEach void close() throws Exception { store.close(); }
    JSONObject ship(String id, String zh, String en, String py) {
        return JSONObject.of("id", id, "names", JSONObject.of("zh", zh, "en", en, "py", py), "sections", JSONObject.of(
            "hull", JSONArray.of(JSONObject.of("level", 10, "health", 50600, "maxSpeed", 33, "upgrade", JSONObject.of("hull", "B"))),
            "artillery", JSONArray.of(JSONObject.of("barrelDiameter", 203, "shotDelay", 5.5, "maxDist", 15.8, "upgrade", JSONObject.of("art", "B"))),
            "ability", JSONArray.of(JSONObject.of("rls", JSONArray.of(JSONObject.of("workTime", 35, "distShip", 10)))),
            "airDefense", JSONArray.of(JSONObject.of("sumBubbleCount", 5))));
    }
    String publish(String suffix, String version, JSONObject... ships) throws Exception {
        String name = "442-20261023T062912Z-" + suffix;
        Path folder = root.resolve("data/wows/" + name); Files.createDirectories(folder);
        var metadata = JSONObject.of("source", "https://iwarship.net/wowsdb/index", "server", "ASIA", "gameVersion", version, "shipCount", ships.length);
        Files.writeString(folder.resolve("ships.json"), JSONObject.of("metadata", metadata, "ships", Arrays.asList(ships)).toJSONString());
        Files.writeString(root.resolve("data/wows/current.json"), JSONObject.of("snapshot", name).toJSONString());
        return name;
    }
    void fixture() throws Exception {
        publish("aaaabbbb", "0.15.9.0", ship("PASC020", "得梅因", "Des Moines", "dmy"), ship("PJSB018", "大和", "Yamato", "dh"),
            ship("PASB017", "蒙大拿", "Montana", "mdn"), ship("PJSB999", "ARP 大和", "ARP Yamato", "arpdh"));
    }

    @Test void findsChineseEnglishIdAndPinyinWithoutNetworkAndAvoidsNestedNameConfusion() throws Exception {
        fixture(); var service = new LocalShipDataService(root);
        for (String query : List.of("得梅因的主炮装填", "DES MOINES 主炮", "PASC020 射程", "dmy 主炮")) {
            var result = service.query(query).orElseThrow(); assertEquals(1, result.ships().size()); assertEquals("PASC020", result.ships().get(0).id());
            assertEquals(5.5, result.ships().get(0).sections().getJSONArray("artillery").getJSONObject(0).getDoubleValue("shotDelay"));
        }
        assertEquals("PJSB999", service.query("ARP 大和主炮").orElseThrow().ships().get(0).id());
        assertEquals(1, service.query("ARP 大和主炮").orElseThrow().ships().size());
        assertEquals(2, service.query("比较大和与ARP 大和主炮").orElseThrow().ships().size());
        assertTrue(service.query("今天吃什么").isEmpty()); assertTrue(service.query("mydmyword").isEmpty());
        assertTrue(service.query("战舰世界").orElseThrow().needsClarification());
    }

    @Test void comparesThreeShipsKeepsModuleAndVersionAndSelectsRelevantSections() throws Exception {
        fixture(); var service = new LocalShipDataService(root);
        var compare = service.query("比较得梅因、大和与蒙大拿的主炮").orElseThrow();
        assertEquals(3, compare.ships().size()); assertFalse(compare.needsClarification());
        assertEquals("0.15.9.0", compare.metadata().getString("gameVersion"));
        assertTrue(compare.ships().stream().allMatch(ship -> ship.sections().getJSONArray("artillery").getJSONObject(0).getJSONObject("upgrade").containsKey("art")));
        var radar = service.query("得梅因的雷达持续时间").orElseThrow().ships().get(0);
        assertTrue(radar.sections().containsKey("ability")); assertFalse(radar.sections().containsKey("airDefense"));
        assertTrue(service.query("得梅因、大和、蒙大拿、ARP 大和一起比较").orElseThrow().ships().size() <= 3);
    }

    @Test void ambiguousNamesRequireConfirmationAndLargeRecordsStayBounded() throws Exception {
        var a = ship("ONE", "同名舰", "Same ship", "same"); var b = ship("TWO", "同名舰", "Same ship", "same");
        publish("aaaabbbb", "v1", a, b, ship("THREE", "第三舰", "Third", "third"), ship("FOUR", "第四舰", "Fourth", "fourth"));
        var service = new LocalShipDataService(root);
        // Both configurations remain distinct; no arbitrary first result is selected.
        assertTrue(service.query("同名舰主炮").orElseThrow().needsClarification());
        assertEquals(2, service.query("同名舰主炮").orElseThrow().candidates().size());
        assertTrue(service.query("同名舰、第三舰和第四舰").orElseThrow().needsClarification());
        var huge = ship("HUGE", "大数据舰", "Huge", "huge");
        huge.getJSONObject("sections").getJSONArray("artillery").getJSONObject(0).put("largeField", "x".repeat(20000));
        publish("ccccdddd", "v2", huge);
        var result = service.query("大数据舰主炮").orElseThrow().ships().get(0);
        assertTrue(result.limited()); assertTrue(result.sections().toJSONString().length() < 9500);
        assertEquals(5.5, result.sections().getJSONArray("artillery").getJSONObject(0).getDoubleValue("shotDelay"));
    }

    @Test void newPointerReloadsAndCorruptSnapshotKeepsLastGoodData() throws Exception {
        fixture(); var service = new LocalShipDataService(root);
        assertEquals("0.15.9.0", service.query("得梅因").orElseThrow().metadata().getString("gameVersion"));
        publish("ccccdddd", "next", ship("PASC020", "得梅因", "Des Moines", "dmy"));
        assertEquals("next", service.query("得梅因").orElseThrow().metadata().getString("gameVersion"));
        Path folder = root.resolve("data/wows/442-20261023T062912Z-eeeeffff"); Files.createDirectories(folder); Files.writeString(folder.resolve("ships.json"), "{}");
        Files.writeString(root.resolve("data/wows/current.json"), "{\"snapshot\":\"442-20261023T062912Z-eeeeffff\"}");
        assertEquals("next", service.query("得梅因").orElseThrow().metadata().getString("gameVersion"));
        assertFalse(service.status().get("warning").toString().isBlank());
        Files.writeString(root.resolve("data/wows/current.json"), "{\"snapshot\":\"../../escape\"}");
        assertTrue((boolean)service.status().get("available"));
    }

    @Test void missingSnapshotIsSilentAndDisabledSettingPersistsInSql() throws Exception {
        var service = new LocalShipDataService(root); assertFalse((boolean)service.status().get("available"));
        assertFalse(service.matches("得梅因")); assertTrue(service.query("得梅因").isEmpty());
        fixture(); assertTrue(store.matchesShips("得梅因"));
        store.saveShipDataEnabled(false); assertFalse(store.matchesShips("得梅因"));
        assertTrue(store.exportSql().contains("INSERT INTO bot_wows(id,enabled) VALUES(1,false)"));
        store.close(); store = new BotConfigurationService(root.toString(), new OneBotProperties()); assertFalse(store.shipDataEnabled());
        assertTrue(store.queryShips("得梅因").isPresent()); // Admin preview works while chat feature is off.
    }

    @Test void customKnowledgeWinsAndPrivateAndGroupChatReceiveLocalShipData() throws Exception {
        fixture();
        var client = mock(DeepSeekClient.class); when(client.isConfigured()).thenReturn(true);
        var requests = new ArrayList<List<JSONObject>>();
        when(client.chat(anyList(), anyString(), eq(false))).thenAnswer(call -> { requests.add(List.copyOf(call.getArgument(0))); return "主炮装填为5.5秒"; });
        when(client.chat(anyList(), anyString(), eq(false), anyInt())).thenAnswer(call -> { requests.add(List.copyOf(call.getArgument(0))); return "主炮装填为5.5秒"; });
        var ai = new AiChatService(); ReflectionTestUtils.setField(ai, "deepSeekClient", client); ReflectionTestUtils.setField(ai, "configuration", store);
        ReflectionTestUtils.setField(ai, "memory", new ConversationMemoryService(store, client));
        assertNotNull(ai.chat("onebot:100:private:200", "得梅因主炮装填"));
        store.saveGroup(GroupAutoReplyTests.policy("300", "ALL", 10, 1));
        assertTrue(new GroupAutoReplyService(store, client).reply("100", "300", "200", "得梅因主炮装填", false, false, false).isPresent());
        for (var messages : requests) { assertTrue(messages.toString().contains("shotDelay")); assertTrue(messages.toString().contains("0.15.9.0")); }
        store.saveKnowledgeBase(null, new KnowledgeInput("角色档案", "得梅因", true));
        var messages = new ArrayList<JSONObject>(); new KnowledgeBaseService(store).augment(messages, "得梅因主炮装填");
        assertTrue(messages.toString().contains("角色档案")); assertFalse(messages.toString().contains("shotDelay"));
        assertTrue(new KnowledgeBaseService(store).matches("得梅因"));
    }
}

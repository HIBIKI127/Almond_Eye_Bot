package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.QQApiClient;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WowsReportOutputTests {
    @TempDir Path directory;
    TagImageTests.Store store;
    WowsStatsService stats;
    WowsReportRenderer renderer;
    BotReplyService replies;
    GroupAutoReplyService groups;
    WowsStatsService.Result report;
    @BeforeEach void setup() throws Exception {
        store=new TagImageTests.Store(directory);
        stats=mock(WowsStatsService.class); renderer=mock(WowsReportRenderer.class); groups=mock(GroupAutoReplyService.class);
        replies=new BotReplyService(mock(AiChatService.class),mock(LocalImageService.class),mock(WikiService.class),store,groups);
        ReflectionTestUtils.setField(replies,"wowsStats",stats); ReflectionTestUtils.setField(replies,"wowsRenderer",renderer);
        report=sample(false);
        when(stats.queryResult(anyString())).thenReturn(report);
        when(renderer.isEnabled()).thenReturn(true); when(renderer.render(any())).thenReturn(new byte[]{1,2,3});
    }
    @AfterEach void close() throws Exception { store.close(); }
    static WowsStatsService.Result sample(boolean recent) {
        return new WowsStatsService.Result("亚服 Test_Player（2000000123）\n" + (recent ? "近期 7 天随机战（累计快照差值）\n实际采集区间：2026-10-16 22:00 北京时间 → 2026-10-23 22:00 北京时间\n非逐局日志，起点可能比请求提前最多2小时；更新延迟以官方数据为准。\n" : "账号总体战绩\n所有模式总场次：12345\n以下为累计随机战（pvp，含单野与组队）：\n")
                + "随机战：12000 场｜胜场：6600｜胜率：55.00%\n场均伤害：78000｜场均击沉：1.24\n存活率：35.20%｜K/D：1.91｜场均经验：1800（含高级账号加成）\n"
                + "数据：WG 官方 API；采集：2026-10-23 22:00 北京时间\n官方更新时间：2026-10-23 21:55 北京时间\n"
                + "WoWS Numbers 页面（仅链接，非本次数据源）：https://asia.wows-numbers.com/player/2000000123,Test_Player/",
                "炮声挺响。别急着自封元帅！", true);
    }
    List<Map<String,Object>> send(boolean group, boolean mention) {
        var event=JSONObject.of("self_id",100,"user_id",200,"group_id",300,"message_id",789,
                "message_type",group?"group":"private","message",mention
                        ? com.alibaba.fastjson2.JSONArray.of(JSONObject.of("type","at","data",JSONObject.of("qq","100")),
                            JSONObject.of("type","text","data",JSONObject.of("text","战绩查询 亚服 123 总体")))
                        : "战绩查询 亚服 123 总体");
        List<Map<String,Object>> sent=new ArrayList<>();
        new OneBotMessageService(store,replies,groups).handleEvent(event,(action,params)->sent.add(params));
        return sent;
    }

    @Test void imageIsOneMessageThenCommentaryIsOneMessageWithOnlyFirstQuoted() {
        var sent=send(false,true);
        assertEquals(2,sent.size());
        assertTrue(sent.get(0).toString().contains("base64://AQID"));
        assertTrue(sent.get(0).toString().contains("type=reply"));
        assertFalse(sent.get(1).toString().contains("type=reply"));
        assertTrue(sent.get(1).toString().contains("炮声挺响。别急着自封元帅！"));
        verify(stats).queryResult("亚服 123 总体");
    }

    @Test void renderFailureFallsBackToSingleCompleteReportWithoutCallingStatsAgain() throws Exception {
        when(renderer.render(any())).thenThrow(new IOException("模拟缺少字体"));
        var sent=send(false,false);
        assertEquals(2,sent.size());
        assertTrue(sent.get(0).toString().contains(report.report()));
        assertFalse(sent.get(0).toString().contains(report.commentary()));
        assertTrue(sent.get(1).toString().contains(report.commentary()));
        verify(stats).queryResult(anyString());
    }

    @Test void textModeAndErrorNoticesRemainOneMessageAndNoFakeCommentary() {
        when(renderer.isEnabled()).thenReturn(false);
        assertEquals(2,send(false,false).size());
        when(stats.queryResult(anyString())).thenReturn(new WowsStatsService.Result("历史快照不足。\n请等待数据积累。", "", false));
        var sent=send(false,false);
        assertEquals(1,sent.size()); assertTrue(sent.get(0).toString().contains("历史快照不足。\n请等待数据积累。"));
        verify(renderer).isEnabled();
        verifyNoMoreInteractions(renderer);
    }

    @Test void webReturnsOrderedMessagesWhileKeepingLegacyFirstMessageFields() {
        var web=new WebMessageService(); ReflectionTestUtils.setField(web,"replies",replies);
        var result=web.handleMessage("test","战绩查询 亚服 123 总体");
        assertEquals("image",result.get("type")); assertEquals("AQID",result.get("imageBase64"));
        var messages=(List<?>)result.get("messages"); assertEquals(2,messages.size());
        assertEquals("image",((Map<?,?>)messages.get(0)).get("type"));
        assertEquals(List.of("锐评："+report.commentary()),((Map<?,?>)messages.get(1)).get("parts"));
    }

    @Test void officialPrivateTransportSendsImageBeforeUnsplitCommentary() {
        var qq=new QQMessageService(); var api=mock(QQApiClient.class);
        ReflectionTestUtils.setField(qq,"replies",replies); ReflectionTestUtils.setField(qq,"qqApi",api);
        qq.handle(JSONObject.of("id","message1","message_type",0,"author",JSONObject.of("user_openid","user1"),"content","战绩查询 亚服 123 总体"));
        var order=inOrder(api); order.verify(api).sendPrivateImage("user1","AQID");
        order.verify(api).sendPrivateMsg("user1","锐评："+report.commentary()); order.verifyNoMoreInteractions();
    }

    @Test void disablingGroupAfterReportSuppressesFollowupCommentary() {
        var policy=GroupAutoReplyTests.policy("300","ALL",10,1); store.saveGroup(policy);
        List<Map<String,Object>> sent=new ArrayList<>();
        var event=JSONObject.of("self_id",100,"user_id",200,"group_id",300,"message_type","group","message","战绩查询 亚服 123 总体");
        new OneBotMessageService(store,replies,groups).handleEvent(event,(action,params)-> {
            sent.add(params); store.deleteGroup("100","300");
        });
        assertEquals(1,sent.size()); assertTrue(sent.get(0).toString().contains("type=image"));
    }

    @Test void localRendererCreatesReadableOverallAndRecentPngWithoutIncludingRoast() throws Exception {
        var actual=new WowsReportRenderer();
        for (boolean recent: List.of(false,true)) {
            byte[] bytes=actual.render(sample(recent));
            var png=ImageIO.read(new ByteArrayInputStream(bytes));
            assertNotNull(png); assertEquals(1200,png.getWidth()); assertTrue(png.getHeight()>600 && png.getHeight()<4096);
            assertTrue(bytes.length < 2 * 1024 * 1024);
            Path preview=Path.of("target",recent?"wows-recent-preview.png":"wows-overall-preview.png");
            Files.write(preview,bytes);
        }
        assertThrows(IOException.class,()->actual.render(new WowsStatsService.Result("错误", "",false)));
    }
}

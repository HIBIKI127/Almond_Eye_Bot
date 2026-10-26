package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncMemoryTests {
    @TempDir Path directory;
    TagImageTests.Store store;
    DeepSeekClient client;
    String owner=ConversationMemoryService.qqOwner("100","200");
    @BeforeEach void setup() throws Exception {
        store=new TagImageTests.Store(directory);
        client=mock(DeepSeekClient.class); when(client.isConfigured()).thenReturn(true);
        store.saveGroup(GroupAutoReplyTests.policy("300","ALL",1000,3));
    }
    @AfterEach void close() throws Exception { store.close(); }
    List<MemoryRecord> personal(int count) {
        List<MemoryRecord> records=new ArrayList<>();
        for(int i=0;i<count;i++) records.add(new MemoryRecord("用户问题"+i,"机器人回答"+i,false));
        return records;
    }
    List<GroupMemoryRecord> group(int count) {
        List<GroupMemoryRecord> records=new ArrayList<>();
        for(int i=0;i<count;i++) records.add(new GroupMemoryRecord("200","昵称","群消息"+i,i,false,false));
        return records;
    }

    @Test void onebotSendsAnswerBeforeBothSummariesAndAppendsWhileWorkersAreBlocked() throws Exception {
        String owner=ConversationMemoryService.groupOwner("100","300","200");
        String key=GroupMemoryService.owner("100","300");
        store.replaceMemories(owner,personal(49)); store.replaceGroupMemories(key,group(99));
        CountDownLatch entered=new CountDownLatch(2), release=new CountDownLatch(1);
        when(client.chat(anyList(),anyString(),eq(false),anyInt())).thenReturn("正常回答");
        when(client.summarize(anyList())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return "完整后台摘要";
        });
        var auto=new GroupAutoReplyService(store,client);
        var replies=new BotReplyService(mock(AiChatService.class),mock(LocalImageService.class),mock(WikiService.class),store,auto);
        var messages=new OneBotMessageService(store,replies,auto,new GroupMemoryService(store,client));
        List<Map<String,Object>> sent=new ArrayList<>();
        try {
            messages.handleEvent(JSONObject.of("self_id",100,"user_id",200,"group_id",300,"message_type","group","message","继续聊"),
                    (action,params) -> { verify(client,never()).summarize(anyList()); sent.add(params); });
            assertEquals(1,sent.size()); assertTrue(sent.get(0).toString().contains("正常回答"));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            new ConversationMemoryService(store,client).remember(owner,"摘要时的新问题","新回答",()->true);
            new GroupMemoryService(store,client).record("100","300","201","另一个人","摘要时的新群消息",123,false);
            assertEquals(51,store.memories(owner).size()); assertEquals(102,store.groupMemories(key).size());
            release.countDown(); assertTrue(store.summaries().awaitIdle(5000));
            assertEquals(2,store.memories(owner).size()); assertEquals("摘要时的新问题",store.memories(owner).get(1).userText());
            assertEquals(3,store.groupMemories(key).size());
            assertEquals("正常回答",store.groupMemories(key).get(1).content());
            assertEquals("摘要时的新群消息",store.groupMemories(key).get(2).content());
            verify(client,times(2)).summarize(anyList());
        } finally { release.countDown(); }
    }

    @Test void clearDuringSummaryCannotResurrectEvenIdenticalRecreatedHistory() throws Exception {
        var memory=new ConversationMemoryService(store,client);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(client.summarize(anyList())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "旧摘要不得复活";});
        store.replaceMemories(owner,personal(49));
        try {
            memory.remember(owner,"最后问题","最后回答",()->true);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var old=store.memories(owner); memory.clear(owner); store.replaceMemories(owner,old);
            release.countDown(); assertTrue(store.summaries().awaitIdle(5000));
            assertEquals(old,store.memories(owner)); assertFalse(store.memories(owner).get(0).summary());
        } finally { release.countDown(); }
    }

    @Test void groupDisabledWhileSummaryRunsKeepsOriginalAndNewRows() throws Exception {
        String key=GroupMemoryService.owner("100","300");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(client.summarize(anyList())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return "不应应用";});
        var memory=new GroupMemoryService(store,client);store.replaceGroupMemories(key,group(99));
        try {
            memory.record("100","300","200","","第100条",100,false);
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            memory.record("100","300","201","","新记录",101,false);
            store.deleteGroup("100","300");release.countDown();assertTrue(store.summaries().awaitIdle(5000));
            assertEquals(101,store.groupMemories(key).size());assertFalse(store.groupMemories(key).get(0).summary());
        } finally {release.countDown();}
    }

    @Test void incompleteSummaryLeavesAllRowsAndNextReplyStillWorksWithoutRepeatedCalls() throws Exception {
        when(client.summarize(anyList())).thenThrow(new DeepSeekClient.IncompleteSummaryException("length"));
        store.replaceMemories(owner,personal(49)); var memory=new ConversationMemoryService(store,client);
        memory.remember(owner,"最后","回答",()->true); assertTrue(store.summaries().awaitIdle(5000));
        memory.remember(owner,"继续","仍能回答",()->true); assertTrue(store.summaries().awaitIdle(5000));
        assertEquals(51,store.memories(owner).size());verify(client).summarize(anyList());
        assertEquals(100,memory.context(owner,50,4000,4000).size());
    }

    @Test void migratesOldHardLimitsAndOverflowSurvivesSqlBackupRestore() throws Exception {
        store.close();
        String url="jdbc:h2:file:"+directory.resolve("data/bot-config").toString().replace('\\','/');
        try(var db=DriverManager.getConnection(url,"sa","");var sql=db.createStatement()) {
            sql.execute("ALTER TABLE conversation_memory ADD CONSTRAINT legacy_person_limit CHECK(ordinal<50)");
            sql.execute("ALTER TABLE group_memory ADD CONSTRAINT legacy_group_limit CHECK(ordinal<100)");
        }
        store=new TagImageTests.Store(directory);
        store.replaceMemories(owner,personal(55));store.replaceGroupMemories("group:100:300",group(105));
        String backup=store.exportSql();
        Path restored=directory.resolve("restored");java.nio.file.Files.createDirectories(restored.resolve("data"));
        try(var db=DriverManager.getConnection("jdbc:h2:file:"+restored.resolve("data/bot-config").toString().replace('\\','/'),"sa","");
            var sql=db.createStatement()) {sql.execute(backup);}
        try(var copy=new TagImageTests.Store(restored)) {
            assertEquals(55,copy.memories(owner).size());assertEquals(105,copy.groupMemories("group:100:300").size());
        }
    }

    @Test void longInputsUseWholeRecordBatchesAndPreserveUnsummarizedTail() throws Exception {
        List<MemoryRecord> records=new ArrayList<>();
        for(int i=0;i<50;i++)records.add(new MemoryRecord("问题"+i+"字".repeat(3500),"回答"+i+"字".repeat(3500),false));
        store.replaceMemories(owner,records);
        when(client.summarize(anyList())).thenAnswer(call->{
            List<JSONObject> messages=call.getArgument(0);
            assertTrue(messages.get(1).getString("content").length()<50000);return "长记录摘要";
        });
        new ConversationMemoryService(store,client).remember(owner,"新问题","新回答",()->true);
        assertTrue(store.summaries().awaitIdle(5000));
        var remaining=store.memories(owner);assertTrue(remaining.get(0).summary());
        assertTrue(remaining.size()>2);assertEquals("新问题",remaining.get(remaining.size()-1).userText());
    }
}

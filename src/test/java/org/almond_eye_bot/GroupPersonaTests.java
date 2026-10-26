package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.DeepSeekClient;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.controller.AdminController;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupPersonaTests {
    @TempDir Path directory;
    BotConfigurationService store;
    DeepSeekClient client;
    Instant now=Instant.now();
    Clock clock;
    @BeforeEach void setup() throws Exception {
        store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        store.saveGroup(GroupAutoReplyTests.policy("300","ALL",50,3));
        store.saveGroup(GroupAutoReplyTests.policy("301","ALL",50,3));
        client=mock(DeepSeekClient.class);when(client.isConfigured()).thenReturn(true);
        clock=mock(Clock.class);when(clock.instant()).thenAnswer(call->now);when(clock.millis()).thenAnswer(call->now.toEpochMilli());
    }
    @AfterEach void close() throws Exception {store.close();}
    String url() {return "jdbc:h2:file:"+directory.resolve("data/bot-config").toString().replace('\\','/');}
    @Test void scopesPersistResetIndependentlyAndExportRestore() throws Exception {
        store.savePersona(new Persona("私聊专用"));
        store.saveGroupPersona("100","300",new Persona("温柔的杏's\n只属于甲群; 保持自然"));
        store.saveGroupPersona("100","301",new Persona("冷静的乙群角色"));
        var old=store.group("100","300");
        store.saveGroup(new GroupPolicy("101","300","另一账号同群号",true,old.mode(),old.keywords(),old.cooldownSeconds(),old.dailyLimit(),old.maxInputChars(),old.maxOutputTokens(),old.contextTurns(),old.followUpSeconds(),0));
        assertEquals("",store.groupPersona("101","300").prompt());
        assertFalse(store.systemPrompt("101","300").contains("私聊专用"));
        store.saveGroup(GroupAutoReplyTests.policy("300","ALL",50,3));
        assertTrue(store.groupPersona("100","300").prompt().contains("只属于甲群"));
        String sql=store.exportSql();var persona=store.groupPersona("100","300");
        store.close();store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        assertEquals(persona,store.groupPersona("100","300"));
        try(var db=java.sql.DriverManager.getConnection("jdbc:h2:mem:groupPersonaRestore");var statement=db.createStatement()) {
            statement.execute(sql);
            try(var rows=statement.executeQuery("SELECT prompt FROM group_persona WHERE self_id='100' AND group_id='300'")) {
                assertTrue(rows.next());assertEquals(persona.prompt(),rows.getString(1));
            }
        }
        store.saveGroupPersona("100","300",new Persona(" \n "));
        assertTrue(store.systemPrompt("100","300").startsWith("你是一个友好"));
        assertEquals("冷静的乙群角色",store.groupPersona("100","301").prompt());
        assertEquals("私聊专用",store.persona().prompt());
    }
    @Test void upgradeCopiesLegacyPersonaOnceButNewGroupsNeverFollowGlobalChanges() throws Exception {
        store.savePersona(new Persona("升级前共同人设"));store.close();
        try(var db=java.sql.DriverManager.getConnection(url(),"sa","");var sql=db.createStatement()) {
            sql.execute("DELETE FROM bot_migration WHERE version='group_persona_v1'");sql.execute("DELETE FROM group_persona");
        }
        store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        assertEquals("升级前共同人设",store.groupPersona("100","300").prompt());
        assertEquals("升级前共同人设",store.groupPersona("100","301").prompt());
        store.saveGroupPersona("100","300",new Persona("独立新角色"));store.savePersona(new Persona("私聊再次修改"));
        store.observeGroup("100","302","新发现群");
        assertEquals("",store.groupPersona("100","302").prompt());
        store.close();store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        assertEquals("独立新角色",store.groupPersona("100","300").prompt());
        assertEquals("升级前共同人设",store.groupPersona("100","301").prompt());
        assertEquals("",store.groupPersona("100","302").prompt());
    }
    @Test void twoGroupsAndPrivateNeverSharePersonaOrPersonalConversationHistory() {
        store.savePersona(new Persona("私聊角色"));store.saveGroupPersona("100","300",new Persona("甲群温柔角色"));
        store.saveGroupPersona("100","301",new Persona("乙群冷静角色"));
        store.replaceMemories(ConversationMemoryService.qqOwner("100","200"),List.of(new MemoryRecord("私聊秘密旧记录","私聊旧回答",false)));
        List<List<JSONObject>> requests=new ArrayList<>();
        when(client.chat(anyList(),anyString(),eq(false),anyInt())).thenAnswer(call->{requests.add(List.copyOf(call.getArgument(0)));return requests.size()==1?"甲群专属旧回答":"乙群新回答";});
        var service=new GroupAutoReplyService(store,client,clock);
        assertTrue(service.reply("100","300","200","甲群专属旧问题",true,false,false).isPresent());
        assertTrue(service.reply("100","301","200","乙群问题",true,false,false).isPresent());
        assertTrue(requests.get(0).get(0).getString("content").startsWith("甲群温柔角色"));
        assertTrue(requests.get(1).get(0).getString("content").startsWith("乙群冷静角色"));
        assertFalse(requests.get(1).toString().contains("甲群专属"));assertFalse(requests.toString().contains("私聊秘密"));
        service.clearHistory("100","300","200");
        assertTrue(store.memories(ConversationMemoryService.groupOwner("100","300","200")).isEmpty());
        assertEquals(1,store.memories(ConversationMemoryService.groupOwner("100","301","200")).size());
        assertEquals(1,store.memories(ConversationMemoryService.qqOwner("100","200")).size());
    }
    @Test void otherScopesCanChangeDuringRequestButSameScopeAbaChangeDiscardsOldAnswer() {
        store.saveGroupPersona("100","300",new Persona("甲群角色"));
        var service=new GroupAutoReplyService(store,client,clock);
        when(client.chat(anyList(),anyString(),eq(false),anyInt())).thenAnswer(call->{
            store.savePersona(new Persona("私聊修改"));store.saveGroupPersona("100","301",new Persona("乙群修改"));return "甲群有效回答";
        });
        assertTrue(service.reply("100","300","200","第一轮",true,false,false).isPresent());
        now=now.plusSeconds(31);
        when(client.chat(anyList(),anyString(),eq(false),anyInt())).thenAnswer(call->{
            store.saveGroupPersona("100","300",new Persona("临时变化"));store.saveGroupPersona("100","300",new Persona("甲群角色"));return "旧任务回答";
        });
        assertTrue(service.reply("100","300","200","第二轮",true,false,false).isEmpty());
        assertEquals(1,store.memories(ConversationMemoryService.groupOwner("100","300","200")).size());
    }
    @Test void directAiServiceGroupAndReasoningEntrypointsAlsoUseAssignedScope() {
        store.saveGroupPersona("100","300",new Persona("群独立角色"));store.savePersona(new Persona("私聊独立角色"));
        List<List<JSONObject>> requests=new ArrayList<>();
        when(client.chat(anyList(),anyString(),eq(false))).thenAnswer(call->{requests.add(List.copyOf(call.getArgument(0)));return "回答";});
        var ai=new AiChatService();ReflectionTestUtils.setField(ai,"configuration",store);ReflectionTestUtils.setField(ai,"deepSeekClient",client);
        ReflectionTestUtils.setField(ai,"memory",new ConversationMemoryService(store,client));
        assertNotNull(ai.chatWithReasoning("onebot:100:group:300:200","群问题"));
        assertNotNull(ai.chat("onebot:100:private:200","私聊问题"));
        assertTrue(requests.get(0).get(0).getString("content").startsWith("群独立角色"));
        assertTrue(requests.get(1).get(0).getString("content").startsWith("私聊独立角色"));
        assertFalse(requests.get(1).toString().contains("群问题"));
    }
    @Test void authenticatedHttpEndpointsValidateAndOnlyModifySelectedGroup() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdminController(store,client))
                .addFilters(new org.almond_eye_bot.config.AdminAccessFilter("persona-test")).build();
        String path="/admin/api/groups/100/300/persona";
        assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).servletPath(path)).andReturn().getResponse().getStatus());
        var save=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).servletPath(path).contentType("application/json")
                .content("{\"prompt\":\"甲群新人设\"}").header("Authorization","Bearer persona-test").header("X-Requested-With","AlmondAdmin");
        assertEquals(200,mvc.perform(save).andReturn().getResponse().getStatus());
        assertEquals("甲群新人设",store.groupPersona("100","300").prompt());assertEquals("",store.groupPersona("100","301").prompt());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.saveGroupPersona("100","300",new Persona("字".repeat(8001))));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.saveGroupPersona("100","300",new Persona(null)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.groupPersona("100","999"));
        var forbidden=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).servletPath(path).contentType("application/json")
                .content("{\"prompt\":\"不能写入\"}").header("Authorization","Bearer persona-test");
        assertEquals(403,mvc.perform(forbidden).andReturn().getResponse().getStatus());
        store.deleteGroup("100","300");
        store.observeGroup("100","300","重新添加");assertEquals("",store.groupPersona("100","300").prompt());
    }
}

package org.almond_eye_bot;

import org.almond_eye_bot.config.BotConfiguration.Persona;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.almond_eye_bot.client.DeepSeekClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentBoundaryTests {
    @TempDir Path directory;
    BotConfigurationService store;
    @org.junit.jupiter.api.AfterEach void close() throws Exception { if(store!=null) store.close(); }
    @Test void independentPersistentScopesAndExport() throws Exception {
        String exported;
        store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        try {
            store.savePersona(new Persona("private-role"));
            store.saveGroup(GroupAutoReplyTests.policy("300","ALL",50,3));
            store.saveGroupPersona("100","300",new Persona("group-role"));
            store.saveContentBoundary(new Persona("  forbidden-topic's\nrule-two  "));
            assertTrue(store.systemPrompt(false).contains("forbidden-topic's\nrule-two"));
            assertTrue(store.systemPrompt("100","300").contains("forbidden-topic's"));
            assertEquals("private-role",store.persona().prompt());
            assertEquals("group-role",store.groupPersona("100","300").prompt());
            var revision=store.contentBoundary().revision();
            store.savePersona(new Persona("changed-role"));
            assertEquals(revision,store.contentBoundary().revision());
            assertThrows(ResponseStatusException.class,()->store.saveContentBoundary(new Persona(null)));
            assertThrows(ResponseStatusException.class,()->store.saveContentBoundary(new Persona("x".repeat(8001))));
            exported=store.exportSql();
        } finally {store.close(); store=null;}
        store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        try {
            assertEquals("forbidden-topic's\nrule-two",store.contentBoundary().prompt());
            store.saveContentBoundary(new Persona(" "));
            assertFalse(store.systemPrompt(false).contains("forbidden-topic"));
            assertEquals("changed-role",store.persona().prompt());
        } finally {store.close(); store=null;}
        try(var db=java.sql.DriverManager.getConnection("jdbc:h2:mem:boundaryRestore");var sql=db.createStatement()) {
            sql.execute(exported);
            try(var row=sql.executeQuery("SELECT prompt FROM bot_content_boundary WHERE id=1")) {
                assertTrue(row.next()); assertEquals("forbidden-topic's\nrule-two",row.getString(1));
            }
        }
    }
    @Test void changingBoundaryDiscardsInFlightPrivateAndGroupAnswersIncludingABA() throws Exception {
        store=new BotConfigurationService(directory.toString(),new OneBotProperties());
        try {
            var client=mock(DeepSeekClient.class); when(client.isConfigured()).thenReturn(true);
            var service=new AiChatService();
            ReflectionTestUtils.setField(service,"configuration",store);
            ReflectionTestUtils.setField(service,"deepSeekClient",client);
            ReflectionTestUtils.setField(service,"memory",new ConversationMemoryService(store,client));
            when(client.chat(anyList(),anyString(),eq(false))).thenAnswer(call->{
                store.saveContentBoundary(new Persona("temporary")); store.saveContentBoundary(new Persona("")); return "old-answer";
            });
            assertNull(service.chat("test","hello"));
            store.saveGroup(GroupAutoReplyTests.policy("300","ALL",50,3));
            when(client.chat(anyList(),anyString(),eq(false),anyInt())).thenAnswer(call->{
                store.saveContentBoundary(new Persona("new-rule")); return "old-answer";
            });
            assertTrue(new GroupAutoReplyService(store,client,java.time.Clock.systemUTC()).reply("100","300","200","hello",true,false,false).isEmpty());
        } finally {store.close(); store=null;}
    }
}

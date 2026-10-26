package org.almond_eye_bot;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.OneBotClient;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.handler.OneBotMessageHandler;
import org.almond_eye_bot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OneBotSendOrderTests {
    @Test void waitsForQuotedHeadBeforeSendingRemainingParts() throws Exception { exercise(false,false); }
    @Test void failedQuotedHeadStopsRemainingParts() throws Exception { exercise(true,false); }
    @Test void disconnectStopsRemainingParts() throws Exception { exercise(false,true); }
    @Test void timedOutHeadStopsRemainingParts() throws Exception { exercise(false,false,true); }
    void exercise(boolean fail,boolean disconnect) throws Exception { exercise(fail,disconnect,false); }
    void exercise(boolean fail,boolean disconnect,boolean timeout) throws Exception {
        var properties=new OneBotProperties(); properties.setTimeout(timeout?700:5000);
        var client=new OneBotClient(properties);
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("ordering");when(session.isOpen()).thenReturn(true);
        var requests=new LinkedBlockingQueue<JSONObject>();
        doAnswer(call->{requests.add(JSONObject.parseObject(((TextMessage)call.getArgument(0)).getPayload()));return null;}).when(session).sendMessage(any());
        var service=mock(OneBotMessageService.class);var done=new CountDownLatch(1);
        doAnswer(call->{
            BiConsumer<String,Map<String,Object>> send=call.getArgument(1);
            try {
                send.accept("send_group_msg",Map.of("group_id",300,"message",List.of(
                        Map.of("type","reply","data",Map.of("id","5")),Map.of("type","text","data",Map.of("text","head")))));
                send.accept("send_group_msg",Map.of("group_id",300,"message",List.of(Map.of("type","text","data",Map.of("text","tail")))));
            } finally {done.countDown();}
            return null;
        }).when(service).handleEvent(any(),any());
        var handler=new OneBotMessageHandler(client,service,mock(BotConfigurationService.class));
        try {
            handler.handleMessage(session,new TextMessage(JSONObject.of("post_type","message","self_id",100,"user_id",200,
                    "group_id",300,"message_id",5,"message_type","group").toJSONString()));
            var head=requests.poll(2,TimeUnit.SECONDS);assertNotNull(head);
            assertEquals("reply",head.getJSONObject("params").getJSONArray("message").getJSONObject(0).getString("type"));
            assertNull(requests.poll(150,TimeUnit.MILLISECONDS),"Tail sent before head acknowledgement");
            if(disconnect) client.disconnected(session);
            else if(!timeout) handler.handleMessage(session,new TextMessage(JSONObject.of("echo",head.getString("echo"),"status",fail?"failed":"ok","retcode",fail?100:0).toJSONString()));
            if(!fail&&!disconnect&&!timeout){
                var tail=requests.poll(2,TimeUnit.SECONDS);assertNotNull(tail);
                assertEquals("tail",tail.getJSONObject("params").getJSONArray("message").getJSONObject(0).getJSONObject("data").getString("text"));
                handler.handleMessage(session,new TextMessage(JSONObject.of("echo",tail.getString("echo"),"status","ok","retcode",0).toJSONString()));
            }
            assertTrue(done.await(2,TimeUnit.SECONDS));assertTrue(requests.isEmpty());
        } finally {handler.destroy();}
    }
}

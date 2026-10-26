package org.almond_eye_bot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.almond_eye_bot.client.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.almond_eye_bot.service.*;
import org.almond_eye_bot.service.BotConfigurationService.TwitterSubscription;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TwitterSubscriptionTests {
 @TempDir Path directory;BotConfigurationService store;TwitterClient api;OneBotClient sender;TwitterSubscriptionService service;
 @BeforeEach void setup()throws Exception {
  store=new BotConfigurationService(directory.toString(),new OneBotProperties());store.saveGroup(GroupAutoReplyTests.policy("300","ALL",50,3));
  api=mock(TwitterClient.class);sender=mock(OneBotClient.class);when(api.ready()).thenReturn(true);when(sender.accountConnected("100")).thenReturn(true);
  when(api.lookup("Example")).thenReturn(new TwitterClient.Account("900","Example"));
  when(api.latest("900","")).thenReturn(Optional.of(new TwitterClient.Post("1000","hello","2026-10-25T00:00:00Z")));
  service=new TwitterSubscriptionService(store,api,sender);
 }
 @AfterEach void close()throws Exception{service.close();store.close();}
 BotReplyService.Context context(boolean group){return new BotReplyService.Context("onebot:100:"+(group?"group:300:200":"private:200"),"100",group?"300":null,"200",group,true,false);}
 String cmd(String text,boolean group){return service.command(text,context(group)).orElseThrow();}
 void add(){assertTrue(cmd("推特关注 测试 https://x.com/Example",true).contains("已添加"));}
 TwitterSubscription due(){var s=store.twitterSubscriptions().get(0);store.updateTwitterSubscription(s,s.cursor(),0);return store.twitterSubscriptions().get(0);}
 @Test void addBaselineFetchCancelAndScopeIsolation(){
  add();assertEquals("1000",store.twitterSubscriptions().get(0).cursor());assertTrue(cmd("推特获取 测试",true).contains("https://x.com/Example/status/1000"));
  assertTrue(cmd("推特获取 测试",false).contains("未找到"));assertTrue(cmd("推特关注 测试 https://x.com/Example",true).contains("已被订阅"));
  assertTrue(cmd("推特取消 测试",false).contains("未找到"));assertEquals(1,store.twitterSubscriptions().size());
  when(api.ready()).thenReturn(false);assertTrue(cmd("推特取消 测试",true).contains("已取消"));assertTrue(store.twitterSubscriptions().isEmpty());
 }
 @Test void pollOnlyNewLatestAndCommitsAfterSuccess()throws Exception {
  add();due();when(api.latest("900","1000")).thenReturn(Optional.of(new TwitterClient.Post("1002","new","")));
  doAnswer(call->{assertEquals("1000",store.twitterSubscriptions().get(0).cursor());return null;}).when(sender).sendToAccount(anyString(),anyBoolean(),anyString(),anyString());
  service.pollNow();assertEquals("1002",store.twitterSubscriptions().get(0).cursor());service.pollNow();
  verify(sender,times(1)).sendToAccount(eq("100"),eq(true),eq("300"),contains("/status/1002"));
 }
 @Test void failedSendKeepsCursorAndCancelledSubscriptionDoesNotPoll()throws Exception {
  add();due();when(api.latest("900","1000")).thenReturn(Optional.of(new TwitterClient.Post("1002","new","")));
  doThrow(new java.io.IOException()).when(sender).sendToAccount(anyString(),anyBoolean(),anyString(),anyString());
  service.pollNow();assertEquals("1000",store.twitterSubscriptions().get(0).cursor());assertTrue(store.twitterSubscriptions().get(0).lastPoll()>0);
  cmd("推特取消 测试",true);clearInvocations(api);service.pollNow();verify(api,never()).latest(anyString(),anyString());
 }
 @Test void disabledGroupAndDisconnectedAccountNeverFetch() {
  add();due();when(sender.accountConnected("100")).thenReturn(false);clearInvocations(api);service.pollNow();verify(api,never()).latest(anyString(),anyString());
  when(sender.accountConnected("100")).thenReturn(true);store.deleteGroup("100","300");service.pollNow();verify(api,never()).latest(anyString(),anyString());
 }
 @Test void malformedCommandAndUnsafeUrlNeverCallApi(){
  assertTrue(cmd("推特关注 测试 https://evil.example/Example",true).contains("主页链接"));
  assertTrue(cmd("推特关注 测试",true).contains("用法"));assertTrue(cmd("推特获取 名 称",true).contains("用法"));
  assertTrue(service.command("聊聊推特关注的事情",context(true)).isEmpty());verify(api,never()).lookup(anyString());
 }
 @Test void subscriptionDataPersistsAndSqlExportRestores()throws Exception {
  add();String sql=store.exportSql();var expected=store.twitterSubscriptions();store.close();store=new BotConfigurationService(directory.toString(),new OneBotProperties());assertEquals(expected,store.twitterSubscriptions());
  try(var db=java.sql.DriverManager.getConnection("jdbc:h2:mem:twitterRestore");var s=db.createStatement()){
   s.execute(sql);try(var r=s.executeQuery("SELECT alias,cursor_id FROM twitter_subscription")){assertTrue(r.next());assertEquals("测试",r.getString(1));assertEquals("1000",r.getString(2));}
  }
 }
 @Test void commandsBypassRandomChatterAndReturnSingleMessage(){
  var reply=new BotReplyService(mock(AiChatService.class),mock(LocalImageService.class),mock(WikiService.class),store,mock(GroupAutoReplyService.class),()->1);
  ReflectionTestUtils.setField(reply,"twitter",service);
  var outputs=reply.respond("推特关注 测试 https://x.com/Example",context(true));assertEquals(1,outputs.size());assertTrue(outputs.get(0).singleMessage());assertTrue(outputs.get(0).text().contains("已添加"));
 }
 @Test void perConversationCapRejectsBeforePaidApiCalls(){
  for(int i=0;i<10;i++)assertTrue(cmd("推特关注 名称"+i+" https://x.com/Example",true).contains("已添加"));
  clearInvocations(api);assertTrue(cmd("推特关注 超限 https://x.com/Example",true).contains("上限"));verify(api,never()).lookup(anyString());
  assertTrue(cmd("推特关注 同名私聊 https://x.com/Example",false).contains("已添加"));
 }
 @Test void groupsAndPrivateCanUseSameAliasIndependently(){
  add();assertTrue(cmd("推特关注 测试 https://x.com/Example",false).contains("已添加"));
  assertEquals(2,store.twitterSubscriptions().size());cmd("推特取消 测试",true);assertEquals(1,store.twitterSubscriptions().size());
  assertTrue(cmd("推特获取 测试",false).contains("/status/1000"));
 }
 @Test void cancelReaddHasDifferentGenerationAndOldSnapshotCannotWrite(){
  add();var old=store.twitterSubscriptions().get(0);cmd("推特取消 测试",true);add();assertNotEquals(old.generation(),store.twitterSubscriptions().get(0).generation());assertFalse(store.updateTwitterSubscription(old,"1002",0));
 }
}

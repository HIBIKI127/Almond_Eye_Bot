package org.almond_eye_bot.service;

import org.almond_eye_bot.client.*;
import org.almond_eye_bot.service.BotConfigurationService.TwitterSubscription;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

@Service
public class TwitterSubscriptionService implements AutoCloseable {
 private final BotConfigurationService store; private final TwitterClient api; private final OneBotClient sender;
 private final Object[] locks=new Object[32];
 private final AtomicBoolean busy=new AtomicBoolean();
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"twitter-poll");t.setDaemon(true);return t;});
 @Value("${twitter.poll-interval-ms:900000}") private long interval=900000;
 private volatile boolean closed;
 private static final Pattern COMMAND=Pattern.compile("^(推特关注|推特获取|推特取消)(?:[\\s:：]+(.*)|$)",Pattern.UNICODE_CHARACTER_CLASS|Pattern.DOTALL);
 public TwitterSubscriptionService(BotConfigurationService store,TwitterClient api,OneBotClient sender){this.store=store;this.api=api;this.sender=sender;Arrays.setAll(locks,i->new Object());}
 @jakarta.annotation.PostConstruct public void validate(){if(interval<60000||interval>86400000)throw new IllegalArgumentException("推特检查间隔需为60000–86400000毫秒");}
 private Object lock(String self,String target,boolean group,String alias){return locks[Math.floorMod(Objects.hash(self,target,group,alias),locks.length)];}
 public Optional<String> command(String text,BotReplyService.Context context){
  var m=COMMAND.matcher(text.strip());if(!m.matches())return Optional.empty();
  if(context.selfId()==null||context.userId()==null||!context.conversation().startsWith("onebot:"))return Optional.of("推特订阅目前仅支持OneBot群聊和私聊。");
  String args=m.group(2)==null?"":m.group(2).strip();String[] words=args.split("(?U)\\s+");String action=m.group(1);
  if(args.isEmpty()||words.length!=(action.equals("推特关注")?2:1))return Optional.of("用法：推特关注 名称 https://x.com/username；推特获取 名称；推特取消 名称。名称不能包含空格。");
  String alias=words[0];if(!alias.matches("[\\p{L}\\p{N}_-]{1,40}"))return Optional.of("自定名称限1–40个文字、数字、下划线或短横线，不支持空格。");
  String self=context.selfId(),target=context.group()?context.groupId():context.userId();
  synchronized(lock(self,target,context.group(),alias)) {
   var old=find(self,target,context.group(),alias);
   try {
    if(action.equals("推特取消"))return Optional.of(old!=null&&store.deleteTwitterSubscription(old)?"已取消推特订阅："+alias:"未找到此会话中的推特订阅："+alias);
    if(!api.ready())return Optional.of("推特功能未配置，请设置TWITTER_BEARER_TOKEN并启用TWITTER_ENABLED。");
    if(action.equals("推特关注")){
     if(old!=null)return Optional.of("此名称已被订阅，请先取消或更换名称。");
     var all=store.twitterSubscriptions();
     if(all.size()>=100||all.stream().filter(s->s.self().equals(self)&&s.target().equals(target)&&s.group()==context.group()).count()>=10)
         return Optional.of("订阅上限：每个会话10个，全机器人100个。");
     String username=TwitterClient.username(words[1]);var account=api.lookup(username);var latest=api.latest(account.id(),"");
     store.addTwitterSubscription(new TwitterSubscription(self,target,context.group(),alias,account.username(),account.id(),latest.map(TwitterClient.Post::id).orElse(""),UUID.randomUUID().toString(),System.currentTimeMillis()));
     return Optional.of("已添加推特订阅："+alias+" (@"+account.username()+")。定时检查新推文；可用“推特获取 "+alias+"”查看当前最新一条。");
    }
    if(old==null)return Optional.of("未找到此会话中的推特订阅："+alias);
    return Optional.of(api.latest(old.userId(),"").map(p->format(old,p)).orElse("此账号暂时没有可读取的推文。"));
   }catch(IllegalArgumentException|IllegalStateException e){return Optional.of(e.getMessage());}
   catch(Exception e){LoggerFactory.getLogger(getClass()).warn("推特指令失败：{}",e.getClass().getSimpleName());return Optional.of("推特获取失败，请稍后重试。");}
  }
 }
 private TwitterSubscription find(String self,String target,boolean group,String alias){return store.twitterSubscriptions().stream().filter(s->s.self().equals(self)&&s.target().equals(target)&&s.group()==group&&s.alias().equals(alias)).findFirst().orElse(null);}
 private boolean allowed(TwitterSubscription s){var p=s.group()?store.group(s.self(),s.target()):null;return !s.group()||p!=null&&p.enabled();}
 private static String format(TwitterSubscription s,TwitterClient.Post p){
  String text=p.text();if(text.length()>2800)text=text.substring(0,text.offsetByCodePoints(0,Math.min(1400,text.codePointCount(0,text.length()))))+"…";
  return "推特更新｜"+s.alias()+" (@"+s.username()+")\n"+p.created()+"\n\n"+text+"\n\nhttps://x.com/"+s.username()+"/status/"+p.id();
 }
 @Scheduled(fixedDelayString="${twitter.poll-interval-ms:900000}",initialDelayString="${twitter.poll-interval-ms:900000}")
 public void tick(){if(closed||!api.ready()||!busy.compareAndSet(false,true))return;try{worker.execute(()->{try{pollNow();}finally{busy.set(false);}});}catch(RejectedExecutionException e){busy.set(false);}}
 public void pollNow(){
  if(!api.ready()||closed)return;
  int count=0;
  for(var snapshot:store.twitterSubscriptions()) {
   if(closed||Thread.currentThread().isInterrupted())return;
   if(System.currentTimeMillis()-snapshot.lastPoll()<Math.max(60000,interval)||!allowed(snapshot)||!sender.accountConnected(snapshot.self()))continue;
   if(count++>=20)break;
   synchronized(lock(snapshot.self(),snapshot.target(),snapshot.group(),snapshot.alias())){
    var current=find(snapshot.self(),snapshot.target(),snapshot.group(),snapshot.alias());if(!snapshot.equals(current))continue;
    try {
     // Reserve the poll time even on errors; avoid repeated requests after reconnect/restart.
     if(!store.updateTwitterSubscription(current,current.cursor(),System.currentTimeMillis()))continue;
     var post=api.latest(current.userId(),current.cursor());
     if(post.isEmpty())continue;
     synchronized(OneBotDeliveryLocks.target(current.self(),current.group(),current.target())) {
      if(!allowed(current)||find(current.self(),current.target(),current.group(),current.alias())==null)continue;
      sender.sendToAccount(current.self(),current.group(),current.target(),format(current,post.get()));
      store.updateTwitterSubscription(current,post.get().id(),System.currentTimeMillis());
     }
    }catch(Exception e){if(e instanceof InterruptedException){Thread.currentThread().interrupt();return;}LoggerFactory.getLogger(getClass()).warn("推特定时检查或推送失败，保留游标：{}",e.getClass().getSimpleName());}
   }
  }
 }
 @Override @PreDestroy public void close(){closed=true;worker.shutdownNow();}
}

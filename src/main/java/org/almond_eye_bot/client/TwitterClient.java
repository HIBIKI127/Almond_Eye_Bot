package org.almond_eye_bot.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.math.BigInteger;
import java.util.*;

/** Official, read-only X API. User links never become HTTP request destinations. */
@Component
public class TwitterClient {
    @Value("${twitter.enabled:true}") private boolean enabled=true;
    @Value("${twitter.bearer-token:}") private String token="";
    private final RestTemplate rest;
    private volatile long blockedUntil;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public TwitterClient(){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(5000);f.setReadTimeout(15000);rest=new RestTemplate(f);}
    public record Account(String id,String username) {}
    public record Post(String id,String text,String created) {}
    public boolean ready(){return enabled&&!token.isBlank();}
    public static String username(String link) {
        try {
            URI uri=URI.create(link);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null
                    ||uri.getHost()==null||!Set.of("x.com","www.x.com","twitter.com","www.twitter.com","mobile.twitter.com").contains(uri.getHost().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            String path=uri.getPath();if(!path.matches("/[A-Za-z0-9_]{1,15}/?"))throw new IllegalArgumentException();
            String user=path.replace("/","");if(Set.of("home","explore","search","settings","messages","notifications","i","intent","share").contains(user.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            return user;
        }catch(Exception e){throw new IllegalArgumentException("请提供X/Twitter账号主页链接，例如 https://x.com/username（不是推文或关注者列表链接）");}
    }
    public Account lookup(String username){
        if(!username.matches("[A-Za-z0-9_]{1,15}"))throw new IllegalArgumentException("无效推特用户名");
        var data=get("/2/users/by/username/"+username).path("data");
        String id=data.path("id").asString(), canonical=data.path("username").asString();
        if(!id.matches("[0-9]{1,30}")||!canonical.matches("[A-Za-z0-9_]{1,15}"))throw new IllegalStateException("未找到可读取的推特账号");
        return new Account(id,canonical);
    }
    public Optional<Post> latest(String id,String since){
        if(!id.matches("[0-9]{1,30}")||!since.matches("[0-9]{0,30}"))throw new IllegalArgumentException("无效推特账号或游标");
        var body=get("/2/users/"+id+"/tweets?max_results=5&tweet.fields=created_at,note_tweet"+(since.isEmpty()?"":"&since_id="+since));
        var data=body.path("data");
        if(!data.isArray()){
            if(body.path("meta").path("result_count").asInt(-1)==0)return Optional.empty();
            throw new IllegalStateException("推文接口响应结构异常");
        }
        Post latest=null;
        for(var row:data){
            String postId=row.path("id").asString(),text=row.path("note_tweet").path("text").asString();
            if(text.isBlank())text=row.path("text").asString();
            if(!postId.matches("[0-9]{1,30}")||text.isBlank())throw new IllegalStateException("推文缺少ID或正文");
            if(!since.isEmpty()&&new BigInteger(postId).compareTo(new BigInteger(since))<=0)continue;
            if(latest==null||new BigInteger(postId).compareTo(new BigInteger(latest.id()))>0)latest=new Post(postId,text,row.path("created_at").asString());
        }return Optional.ofNullable(latest);
    }
    private JsonNode get(String path){
        if(!ready())throw new IllegalStateException("推特功能未配置，请设置TWITTER_BEARER_TOKEN并启用TWITTER_ENABLED");
        if(System.currentTimeMillis()<blockedUntil)throw new IllegalStateException("推特接口暂处冷却期，请稍后再试");
        var headers=new HttpHeaders();headers.setBearerAuth(token);
        try{
            var response=rest.exchange("https://api.x.com"+path,HttpMethod.GET,new HttpEntity<>(headers),String.class);
            var body=JSON.readTree(response.getBody());if(body==null||!body.isObject()||body.has("errors"))throw new IllegalStateException("推特接口返回错误或不完整数据");return body;
        }catch(HttpStatusCodeException e){
            int status=e.getStatusCode().value();
            if(status==429){blockedUntil=System.currentTimeMillis()+900000;throw new IllegalStateException("推特接口限流，已暂停请求15分钟");}
            if(status==401||status==403){blockedUntil=System.currentTimeMillis()+900000;throw new IllegalStateException("推特API授权失败，请检查Token、读取权限和API额度");}
            if(status==404)throw new IllegalStateException("推特账号不存在或不可访问");
            throw new IllegalStateException("推特接口暂时不可用，请稍后重试");
        }catch(ResourceAccessException e){throw new IllegalStateException("连接推特API失败或超时，请检查服务器网络");}
    }
}

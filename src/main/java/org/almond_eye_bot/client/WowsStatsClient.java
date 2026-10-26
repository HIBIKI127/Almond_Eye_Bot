package org.almond_eye_bot.client;

import org.almond_eye_bot.service.WowsStatsData.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Uses official public account endpoints only; no cookies or user access token. */
@Component
public class WowsStatsClient {
    @Value("${wows-stats.application-id:}") private String applicationId = "";
    private final RestTemplate restTemplate;
    private final SimpleClientHttpRequestFactory factory;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public WowsStatsClient() {
        factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method); connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(5000); factory.setReadTimeout(10000);
        restTemplate = new RestTemplate(factory);
    }
    public boolean isConfigured() { return !applicationId.isBlank(); }
    @Value("${wows-stats.proxy-url:}")
    public void setProxyUrl(String value) {
        if (value.isBlank()) return;
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("WOWS_STATS_PROXY_URL 需为 http://主机:端口");
        factory.setProxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort())));
    }

    public Player player(Region region, String identifier) {
        String id = identifier;
        if (!identifier.matches("[1-9][0-9]{0,9}")) {
            JsonNode list = request(region, "account/list/", Map.of("search", identifier, "type", "exact", "limit", "10")).path("data");
            if (!list.isArray()) throw new IllegalArgumentException("玩家搜索响应异常。");
            id = null;
            for (JsonNode item : list) {
                if (identifier.equalsIgnoreCase(item.path("nickname").asString(""))) {
                    if (id != null) throw new IllegalArgumentException("昵称匹配不唯一，请改用数字账号 ID。");
                    id = Long.toString(number(item, "account_id"));
                }
            }
            if (id == null) throw new IllegalArgumentException("该区服未找到此玩家，请填写完整昵称或数字账号 ID。");
        }
        var node = request(region, "account/info/", Map.of("account_id", id, "fields",
                "account_id,nickname,hidden_profile,updated_at,statistics.battles,statistics.pvp.battles,statistics.pvp.wins,"
                + "statistics.pvp.damage_dealt,statistics.pvp.frags,statistics.pvp.survived_battles,statistics.pvp.xp")).path("data").path(id);
        if (node.isNull() || node.isMissingNode()) throw new IllegalArgumentException("该区服未找到此账号。");
        if (node.path("hidden_profile").asBoolean(false)) throw new IllegalArgumentException("玩家已隐藏战绩，无法查询或进行锐评。");
        long account = number(node, "account_id");
        if (account <= 0 || !Long.toString(account).equals(id)) throw new IllegalArgumentException("账号响应不匹配。");
        String nickname = node.path("nickname").asString("");
        if (!nickname.matches("[A-Za-z0-9_]{3,24}")) throw new IllegalArgumentException("玩家昵称响应异常。");
        var stats = node.path("statistics"); var pvp = stats.path("pvp");
        return new Player(region, account, nickname, number(stats, "battles"),
                new Totals(number(pvp, "battles"), number(pvp, "wins"), number(pvp, "damage_dealt"),
                        number(pvp, "frags"), number(pvp, "survived_battles"), number(pvp, "xp")), number(node, "updated_at"));
    }

    private static long number(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw new IllegalArgumentException("官方返回的统计不完整，暂时无法生成可靠报告。");
        return value.asLong();
    }

    private JsonNode request(Region region, String method, Map<String, String> params) {
        if (!isConfigured()) throw new IllegalArgumentException("尚未配置 WOWS_STATS_APPLICATION_ID，请先在 WG 开发者平台创建应用。");
        var builder = UriComponentsBuilder.fromUriString("https://api.worldofwarships." + region.apiDomain + "/wows/" + method)
                .queryParam("application_id", applicationId);
        params.forEach(builder::queryParam);
        URI uri = builder.build().encode().toUri();
        try {
            byte[] bytes = restTemplate.execute(uri, HttpMethod.GET, req -> {
                req.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
                req.getHeaders().set(HttpHeaders.USER_AGENT, "AlmondEyeBot/1.0 (WoWS public statistics)");
            }, response -> {
                if (!response.getStatusCode().is2xxSuccessful() || response.getHeaders().getContentLength() > 1024 * 1024)
                    throw new IOException("Invalid response");
                byte[] body = response.getBody().readNBytes(1024 * 1024 + 1);
                if (body.length > 1024 * 1024) throw new IOException("Response too large");
                return body;
            });
            var root = JSON.readTree(bytes);
            if (root == null || !root.isObject()) throw new IOException("Invalid JSON");
            if (!"ok".equals(root.path("status").asString(""))) {
                long code = root.path("error").path("code").asLong(0);
                throw new IllegalArgumentException(code == 407 ? "WG 应用标识无效或被限制，请检查 WOWS_STATS_APPLICATION_ID。"
                        : "WG 接口暂不可用或请求受限，请稍后再试。");
            }
            return root;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) {
            // RestTemplate exceptions contain the URL (and app id); do not send them to chat or logs.
            throw new IllegalArgumentException("连接 WG 战绩接口失败，请稍后重试或检查战绩代理配置。");
        }
    }
}

package org.almond_eye_bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Danbooru-compatible tag search. Credentials go to the API only, never image hosts. */
@Service
public class TagImageService {
    private static final int IMAGE_LIMIT = 8 * 1024 * 1024;
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Value("${image-search.enabled:true}") private boolean enabled = true;
    @Value("${image-search.base-url:https://danbooru.donmai.us}") private String baseUrl = "https://danbooru.donmai.us";
    @Value("${image-search.image-hosts:cdn.donmai.us}") private String imageHosts = "cdn.donmai.us";
    @Value("${image-search.login:}") private String login = "";
    @Value("${image-search.api-key:}") private String apiKey = "";
    @Value("${image-search.cooldown-seconds:5}") private int cooldownSeconds = 5;
    private long nextRequest;
    private final RestTemplate restTemplate;
    private final SimpleClientHttpRequestFactory requestFactory;
    @Autowired private BotConfigurationService configuration;
    public record Result(String text, String imageBase64) {}

    public TagImageService() {
        requestFactory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        restTemplate = new RestTemplate(requestFactory);
    }

    @Value("${image-search.proxy-url:}")
    public void setProxyUrl(String value) {
        if (value == null || value.isBlank()) return;
        URI proxy = URI.create(value);
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null || proxy.getUserInfo() != null
                || proxy.getPort() < 1 || proxy.getPort() > 65535 || proxy.getRawQuery() != null
                || proxy.getRawFragment() != null || !(proxy.getPath().isEmpty() || proxy.getPath().equals("/")))
            throw new IllegalArgumentException("IMAGE_SEARCH_PROXY_URL 需为 http://主机:端口");
        requestFactory.setProxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
    }

    public boolean isEnabled() { return enabled; }

    public Result search(String input) { return search(input, "legacy"); }

    public synchronized Result search(String input, String recipient) {
        if (!enabled) return new Result("图片检索已关闭。", null);
        String tags;
        try { tags = normalizeTags(input); }
        catch (IllegalArgumentException error) { return new Result(error.getMessage(), null); }
        long now = System.nanoTime();
        if (nextRequest != 0 && now - nextRequest < 0) return new Result("搜图太频繁，请稍后再试。", null);
        nextRequest = now + Math.max(1, cooldownSeconds) * 1_000_000_000L;
        try {
            URI base = validUri(baseUrl);
            if (base.getRawQuery() != null || base.getRawFragment() != null)
                throw new IllegalArgumentException("Invalid API base URL");
            String root = base.toString().replaceAll("/+$", "");
            String recipientKey = hash(Objects.requireNonNull(recipient));
            Set<String> recent = configuration.recentTagImages(recipientKey, System.currentTimeMillis());
            Set<String> attempted = new HashSet<>();
            boolean duplicates = false;
            URI endpoint = UriComponentsBuilder.fromUriString(root + "/posts.json")
                    .queryParam("tags", tags + " rating:g order:random")
                    .queryParam("limit", 20).build().encode().toUri();
            int attempts = 0;
            // Random batches can overlap. Check the durable history across tags before downloading.
            for (int batch = 0; batch < 3; batch++) {
                boolean batchDuplicates = false;
                JsonNode posts = JSON.readTree(fetch(endpoint, JSON_LIMIT, true));
                if (posts == null || !posts.isArray()) throw new IOException("Invalid post list");
                for (JsonNode post : posts) {
                    if (!"g".equals(post.path("rating").asString()) || post.path("is_deleted").asBoolean(false)
                            || post.path("is_banned").asBoolean(false) || post.path("is_pending").asBoolean(false)
                            || post.path("is_flagged").asBoolean(false) || post.path("id").asLong(0) <= 0) continue;
                    String md5 = post.path("md5").asString("").toLowerCase(Locale.ROOT);
                    String imageKey = hash(root + ":" + (md5.matches("[0-9a-f]{32}") ? "md5:" + md5 : "id:" + post.path("id").asLong()));
                    if (recent.contains(imageKey)) { batchDuplicates = true; duplicates = true; continue; }
                    if (!attempted.add(imageKey)) continue;
                    String ext = post.path("file_ext").asString("");
                    if (!Set.of("jpg", "jpeg", "png", "gif").contains(ext)
                            || post.path("file_size").asLong(0) > IMAGE_LIMIT) continue;
                    String url = post.path("file_url").asString("");
                    URI image;
                    try {
                        image = validUri(url);
                        boolean allowed = image.getHost().equalsIgnoreCase(base.getHost()) || Arrays.stream(imageHosts.split(","))
                                .map(String::strip).anyMatch(host -> host.equalsIgnoreCase(image.getHost()));
                        if (!allowed) continue;
                    } catch (IllegalArgumentException invalid) { continue; }
                    if (++attempts > 3) break;
                    try {
                        byte[] bytes = fetch(image, IMAGE_LIMIT, false);
                        validateImage(bytes);
                        configuration.rememberTagImage(recipientKey, imageKey, System.currentTimeMillis());
                        return new Result("图片来源：" + root + "/posts/" + post.path("id").asLong(),
                                Base64.getEncoder().encodeToString(bytes));
                    } catch (IOException | RuntimeException unusable) {
                        // Try at most three downloadable, general-rated candidates, without retry storms.
                    }
                }
                if (!batchDuplicates || attempts >= 3) break;
            }
            if (duplicates) return new Result("本次未找到未重复且可发送的图片，近期图片不会重复发送，请稍后再试或更换 tag。", null);
            return new Result("未找到可发送的普通分级图片，请检查 tag 或换一个关键词。", null);
        } catch (Exception error) {
            // Do not log URLs/exception messages that could contain service credentials or queries.
            return new Result("图片检索暂时失败，请稍后重试；可检查站点连接或账号的检索权限。", null);
        }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String normalizeTags(String input) {
        if (input == null || input.isBlank() || input.length() > 200)
            throw new IllegalArgumentException("请输入 1–2 个站点 tag，最多 200 字符，例如：搜图 hatsune_miku。多个 tag 用空格分隔。 ");
        String[] tags = input.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (tags.length > 2 || Arrays.stream(tags).anyMatch(tag -> !tag.matches("-?[\\p{L}\\p{N}_()][\\p{L}\\p{N}_()\\-]*"))
                || Arrays.stream(tags).allMatch(tag -> tag.startsWith("-")))
            throw new IllegalArgumentException("请使用 1–2 个普通 tag（空格分隔、词内用下划线），可用 -tag 排除；不支持分级、排序或其他搜索指令。 ");
        return String.join(" ", tags);
    }

    private static URI validUri(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException("HTTPS URL required");
        return uri;
    }

    private byte[] fetch(URI uri, int max, boolean api) {
        return restTemplate.execute(uri, HttpMethod.GET, request -> {
            request.getHeaders().set(HttpHeaders.USER_AGENT, "AlmondEyeBot/1.0 (tag image search)");
            request.getHeaders().setAccept(List.of(api ? MediaType.APPLICATION_JSON : MediaType.ALL));
            if (api && !login.isBlank() && !apiKey.isBlank())
                request.getHeaders().setBasicAuth(login, apiKey, StandardCharsets.UTF_8);
        }, response -> {
            if (!response.getStatusCode().is2xxSuccessful()) throw new IOException("Unexpected HTTP status");
            if (response.getHeaders().getContentLength() > max) throw new IOException("Response too large");
            byte[] bytes = response.getBody().readNBytes(max + 1);
            if (bytes.length > max) throw new IOException("Response too large");
            return bytes;
        });
    }

    private static void validateImage(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Not an image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                long width = reader.getWidth(0), height = reader.getHeight(0);
                if (!Set.of("jpeg", "png", "gif").contains(format) || width <= 0 || height <= 0 || width * height > 20_000_000)
                    throw new IOException("Unsupported image");
                if (reader.read(0) == null) throw new IOException("Invalid image");
            } finally { reader.dispose(); }
        }
    }
}

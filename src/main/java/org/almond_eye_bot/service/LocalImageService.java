package org.almond_eye_bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalImageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalImageService.class);

    private static final String IMAGE_PATH = "assets/chatPic/dahui/";
    private final Random random = new Random();
    private static final String SIZHAIZHENEXIN_IMAGE_PATH = "assets/chatPic/sizhaizhenexin/";
    private static final String RANDOM_UMAMUSUME_PATH = "assets/chatPic/random/umamusume/";

    @Autowired
    private ResourceLoader resourceLoader;

    /**
     * 获取随机打灰图片的 base64 数据
     */
    public String getRandomDahuiImageBase64() {
        try {
            String fileName = "1.jpg";
            String fullPath = "classpath:" + IMAGE_PATH + fileName;

            Resource resource = resourceLoader.getResource(fullPath);

            if (resource.exists()) {
                byte[] imageBytes;
                try (InputStream inputStream = resource.getInputStream()) {
                    imageBytes = inputStream.readAllBytes();
                }

                String base64 = Base64.getEncoder().encodeToString(imageBytes);

                logger.info("获取打灰图片并转换为 base64: {}", fileName);
                return base64;
            } else {
                logger.error("图片文件不存在：{}", fileName);
                throw new RuntimeException("图片文件不存在：" + fileName);
            }
        } catch (IOException e) {
            logger.error("获取图片失败", e);
            throw new RuntimeException("获取图片失败", e);
        }
    }

    /**
     * 获取图片的文件名（用于调试）
     */
    public String getRandomDahuiImageName() {
        int imageIndex = random.nextInt(5) + 1;
        return imageIndex + ".jpg";
    }

    /**
     * 获取打灰图片的文件路径（用于OneBot）
     */
    public String getRandomDahuiImagePath() {
        return "file:///" + System.getProperty("user.dir") + "/src/main/resources/" + IMAGE_PATH + "1.jpg";
    }

    public String getSizhaizhenexinImageBase64() {
        try {
            String fileName = "a4c43ef582e7ec320be9e94b88584c26.jpg";
            String fullPath = "classpath:" + SIZHAIZHENEXIN_IMAGE_PATH + fileName;

            Resource resource = resourceLoader.getResource(fullPath);

            if (resource.exists()) {
                byte[] imageBytes;
                try (InputStream inputStream = resource.getInputStream()) {
                    imageBytes = inputStream.readAllBytes();
                }

                String base64 = Base64.getEncoder().encodeToString(imageBytes);

                logger.info("获取死宅真恶心图片并转换为 base64: {}", fileName);
                return base64;
            } else {
                logger.error("图片文件不存在：{}", fileName);
                throw new RuntimeException("图片文件不存在：" + fileName);
            }
        } catch (IOException e) {
            logger.error("获取图片失败", e);
            throw new RuntimeException("获取图片失败", e);
        }
    }

    /**
     * 获取"死宅真恶心"图片的文件名（用于调试）
     */
    public String getSizhaizhenexinImageName() {
        return "a4c43ef582e7ec320be9e94b88584c26.jpg";
    }

    /**
     * 获取死宅真恶心图片的文件路径（用于OneBot）
     */
    public String getSizhaizhenexinImagePath() {
        return "file:///" + System.getProperty("user.dir") + "/src/main/resources/" + SIZHAIZHENEXIN_IMAGE_PATH + "a4c43ef582e7ec320be9e94b88584c26.jpg";
    }

    public String getRandomUmamusumeImageBase64() {
        try {
            logger.info("开始获取随机马娘图片");

            String imageUrl = fetchRandomUmamusumeImageUrl();

            if (imageUrl == null || imageUrl.isEmpty()) {
                logger.error("未能获取到马娘图片 URL");
                throw new RuntimeException("无法获取马娘图片链接");
            }

            logger.info("获取到马娘图片 URL: {}", imageUrl);

            byte[] imageBytes = downloadImage(imageUrl);

            String fileName = saveImageToLocal(imageBytes, imageUrl);
            logger.info("马娘图片已保存到：{}", fileName);

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            logger.info("马娘图片已转换为 base64");

            return base64;

        } catch (Exception e) {
            logger.error("获取马娘图片失败", e);
            throw new RuntimeException("获取马娘图片失败：" + e.getMessage(), e);
        }
    }

    /**
     * 获取随机马娘图片的文件路径（用于OneBot）
     */
    public String getRandomUmamusumeImagePath() {
        try {
            logger.info("开始获取随机马娘图片路径");

            String imageUrl = fetchRandomUmamusumeImageUrl();

            if (imageUrl == null || imageUrl.isEmpty()) {
                logger.error("未能获取到马娘图片 URL");
                throw new RuntimeException("无法获取马娘图片链接");
            }

            logger.info("获取到马娘图片 URL: {}", imageUrl);

            byte[] imageBytes = downloadImage(imageUrl);

            String fileName = saveImageToLocal(imageBytes, imageUrl);
            logger.info("马娘图片已保存到：{}", fileName);

            return "file:///" + System.getProperty("user.dir") + "/" + RANDOM_UMAMUSUME_PATH + fileName;

        } catch (Exception e) {
            logger.error("获取马娘图片路径失败", e);
            throw new RuntimeException("获取马娘图片路径失败：" + e.getMessage(), e);
        }
    }

    private String fetchRandomUmamusumeImageUrl() {
        String[] apiEndpoints = {
            "https://img.xjh.me/random_img.php?type=acg&return=302",
            "https://api.lolicon.app/setu/v2?tag=%E9%A9%AC%E5%A8%98&limit=1&r18=false"
        };

        for (String apiUrl : apiEndpoints) {
            HttpURLConnection conn = null;
            try {
                logger.info("尝试 API: {}", apiUrl);

                URL url = new URL(apiUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Accept", "*/*");

                int responseCode = conn.getResponseCode();
                logger.info("API {} 响应码：{}", apiUrl, responseCode);

                if (responseCode == 200) {
                    try (InputStream inputStream = conn.getInputStream()) {
                        String response = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

                        if (apiUrl.contains("lolicon.app")) {
                            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(response);
                            int error = json.getIntValue("error", 0);

                            if (error != 0) {
                                logger.warn("Lolicon API 返回错误：{}", json.getString("message"));
                                continue;
                            }

                            com.alibaba.fastjson2.JSONArray data = json.getJSONArray("data");

                            if (data == null || data.isEmpty()) {
                                logger.warn("Lolicon API 返回空数据");
                                continue;
                            }

                            com.alibaba.fastjson2.JSONObject imgData = data.getJSONObject(0);
                            String imageUrl = imgData.getString("original");

                            if (imageUrl != null && !imageUrl.isEmpty()) {
                                imageUrl = normalizeImageUrl(imageUrl);
                                logger.info("成功获取图片 URL: {}", imageUrl);
                                return imageUrl;
                            } else {
                                logger.warn("Lolicon API 返回的图片 URL 为空");
                            }
                        }
                    }
                } else if (responseCode == 302 || responseCode == 301) {
                    String location = conn.getHeaderField("Location");

                    if (location != null && !location.isEmpty()) {
                        location = normalizeImageUrl(location);
                        logger.info("重定向到图片 URL: {}", location);
                        return location;
                    }
                }

            } catch (Exception e) {
                logger.warn("API {} 失败：{}，继续尝试下一个", apiUrl, e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        return null;
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }

        if (imageUrl.startsWith("//")) {
            return "https:" + imageUrl;
        } else if (imageUrl.startsWith("/")) {
            return "https://img.xjh.me" + imageUrl;
        } else if (!imageUrl.startsWith("http://") && !imageUrl.startsWith("https://")) {
            return "https://" + imageUrl;
        }

        return imageUrl;
    }

    private String extractImageUrlFromJson(String json) {
        try {
            com.alibaba.fastjson2.JSONArray posts = com.alibaba.fastjson2.JSON.parseArray(json);

            if (posts.isEmpty()) {
                logger.warn("API 返回空结果");
                return null;
            }

            Random random = new Random();
            int randomIndex = random.nextInt(posts.size());
            com.alibaba.fastjson2.JSONObject post = posts.getJSONObject(randomIndex);

            String fileUrl = post.getString("file_url");

            if (fileUrl == null || fileUrl.isEmpty()) {
                String largeUrl = post.getString("large_file_url");
                if (largeUrl != null && !largeUrl.isEmpty()) {
                    logger.info("使用 large_file_url");
                    if (largeUrl.startsWith("//")) {
                        largeUrl = "https:" + largeUrl;
                    }
                    return largeUrl;
                }

                String previewUrl = post.getString("preview_file_url");
                if (previewUrl != null && !previewUrl.isEmpty()) {
                    logger.info("使用 preview_file_url");
                    if (previewUrl.startsWith("//")) {
                        previewUrl = "https:" + previewUrl;
                    }
                    return previewUrl;
                }

                return null;
            }

            if (fileUrl.startsWith("//")) {
                fileUrl = "https:" + fileUrl;
            } else if (fileUrl.startsWith("/")) {
                fileUrl = "https://danbooru.donmai.us" + fileUrl;
            }

            return fileUrl;

        } catch (Exception e) {
            logger.error("解析 JSON 响应失败", e);
            return null;
        }
    }

    private byte[] downloadImage(String imageUrl) throws IOException {
        logger.info("正在下载图片：{}", imageUrl);

        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        int responseCode = conn.getResponseCode();
        logger.info("图片下载响应码：{}", responseCode);

        if (responseCode != 200) {
            throw new IOException("下载图片失败，响应码：" + responseCode);
        }

        try (InputStream inputStream = conn.getInputStream()) {
            byte[] imageBytes = inputStream.readAllBytes();
            logger.info("图片下载成功，大小：{} bytes", imageBytes.length);
            return imageBytes;
        }
    }

    private String saveImageToLocal(byte[] imageBytes, String imageUrl) throws IOException {
        Path dir = Paths.get(RANDOM_UMAMUSUME_PATH);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            logger.info("创建目录：{}", dir.toAbsolutePath());
        }

        String fileName = generateFileNameFromUrl(imageUrl);
        Path filePath = dir.resolve(fileName);

        Files.write(filePath, imageBytes);
        logger.info("图片已保存：{}", filePath.toAbsolutePath());

        return fileName;
    }

    private String generateFileNameFromUrl(String imageUrl) {
        String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);

        if (fileName.contains("?")) {
            fileName = fileName.substring(0, fileName.indexOf("?"));
        }

        if (fileName.length() > 50) {
            fileName = fileName.substring(0, 50);
        }

        if (!fileName.matches(".*\\.(jpg|jpeg|png|gif|webp).*")) {
            fileName = fileName + "_" + System.currentTimeMillis() + ".jpg";
        }

        return fileName;
    }
}

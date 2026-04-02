package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WikiService {
    private static final Logger logger = LoggerFactory.getLogger(WikiService.class);

    private static final String DEFAULT_WIKI_BASE_URL = "https://ff14.huijiwiki.com";
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\.(jpg|jpeg|png|gif|webp)", Pattern.CASE_INSENSITIVE);

    public class WikiSearchResult {
        public String title;
        public String content;
        public List<String> imageUrls;
        public String url;

        public WikiSearchResult() {
            this.imageUrls = new ArrayList<>();
        }
    }

    public WikiSearchResult searchWiki(String query, String wikiBaseUrl) {
        if (wikiBaseUrl == null || wikiBaseUrl.trim().isEmpty()) {
            wikiBaseUrl = DEFAULT_WIKI_BASE_URL;
        }

        try {
            String searchUrl = wikiBaseUrl + "/api.php?action=query&list=search&srprop=snippet&format=json&srsearch=" +
                    URLDecoder.decode(query, StandardCharsets.UTF_8);

            logger.info("搜索 Wiki: {}", searchUrl);

            String jsonResponse = Jsoup.connect(searchUrl)
                    .timeout(5000)
                    .userAgent("Mozilla/5.0")
                    .ignoreContentType(true)
                    .execute()
                    .body();

            JSONObject json = JSON.parseObject(jsonResponse);
            JSONObject queryObj = json.getJSONObject("query");

            if (queryObj == null) {
                return createNoResult(query, wikiBaseUrl);
            }

            JSONArray searchResults = queryObj.getJSONArray("search");

            if (searchResults == null || searchResults.isEmpty()) {
                return getPageContent(query, wikiBaseUrl);
            }

            JSONObject firstResult = searchResults.getJSONObject(0);
            String title = firstResult.getString("title");

            return getPageContent(title, wikiBaseUrl);

        } catch (Exception e) {
            logger.error("Wiki 搜索失败", e);
            return createErrorResult(query, e.getMessage());
        }
    }

    private WikiSearchResult getPageContent(String title, String wikiBaseUrl) {
        try {
            String pageUrl = wikiBaseUrl + "/wiki/" +
                    URLDecoder.decode(title.replace(" ", "_"), StandardCharsets.UTF_8.toString());

            logger.info("获取页面内容：{}", pageUrl);

            Document doc = Jsoup.connect(pageUrl)
                    .timeout(5000)
                    .userAgent("Mozilla/5.0")
                    .get();

            WikiSearchResult result = new WikiSearchResult();
            result.title = title;
            result.url = pageUrl;

            Element contentDiv = doc.selectFirst("#mw-content-text");
            if (contentDiv != null) {
                result.content = extractTextContent(contentDiv);
                result.imageUrls = extractImages(contentDiv, wikiBaseUrl);
            } else {
                result.content = "未找到相关内容";
            }

            return result;

        } catch (IOException e) {
            logger.error("获取页面内容失败", e);
            WikiSearchResult errorResult = new WikiSearchResult();
            errorResult.title = title;
            errorResult.content = "获取页面失败：" + e.getMessage();
            return errorResult;
        }
    }

    private String extractTextContent(Element contentDiv) {
        StringBuilder text = new StringBuilder();

        Element firstParagraph = contentDiv.selectFirst("p");
        if (firstParagraph != null) {
            text.append(firstParagraph.text()).append("\n\n");
        }

        Elements sections = contentDiv.select(".mw-headline");
        for (int i = 0; i < Math.min(3, sections.size()); i++) {
            Element section = sections.get(i);
            String sectionTitle = section.text();

            Element nextElement = section.parent().nextElementSibling();
            if (nextElement != null) {
                text.append("【").append(sectionTitle).append("】\n");
                text.append(nextElement.text()).append("\n\n");
            }
        }

        if (text.length() > 1900) {
            text = new StringBuilder(text.substring(0, 1900) + "...");
        }

        return text.toString().trim();
    }

    private List<String> extractImages(Element contentDiv, String wikiBaseUrl) {
        List<String> imageUrls = new ArrayList<>();

        Elements images = contentDiv.select("img");
        for (Element img : images) {
            String src = img.attr("src");
            if (src != null && !src.isEmpty()) {
                if (src.startsWith("//")) {
                    src = "https:" + src;
                } else if (!src.startsWith("http")) {
                    src = wikiBaseUrl + src;
                }

                if (IMAGE_URL_PATTERN.matcher(src).find()) {
                    imageUrls.add(src);
                    if (imageUrls.size() >= 3) {
                        break;
                    }
                }
            }
        }

        return imageUrls;
    }

    private WikiSearchResult createNoResult(String query, String wikiBaseUrl) {
        WikiSearchResult result = new WikiSearchResult();
        result.title = "未找到结果";
        result.content = "抱歉，未在 Wiki 中找到关于\"" + query + "\"的信息。\n您可以访问 " + wikiBaseUrl + " 手动查询。";
        result.url = wikiBaseUrl;
        return result;
    }

    private WikiSearchResult createErrorResult(String query, String errorMsg) {
        WikiSearchResult result = new WikiSearchResult();
        result.title = "查询失败";
        result.content = "查询 Wiki 时发生错误：" + errorMsg + "\n请稍后再试。";
        return result;
    }
}

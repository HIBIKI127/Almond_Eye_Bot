package org.almond_eye_bot.controller;

import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.service.BotConfigurationService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class AdminController {
    private final BotConfigurationService configuration;

    private final org.almond_eye_bot.client.DeepSeekClient ai;
    public AdminController(BotConfigurationService configuration, org.almond_eye_bot.client.DeepSeekClient ai) {
        this.configuration = configuration; this.ai = ai;
    }

    @GetMapping({"/admin", "/admin/"})
    public ResponseEntity<Void> page() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "/admin/index.html").build();
    }

    @GetMapping("/admin/api/state")
    public Map<String, Object> state() {
        return Map.of("settings", configuration.settings(), "rules", configuration.rules(), "images", configuration.images(),
                "groups", configuration.groups(), "aiConfigured", ai.isConfigured(), "persona", configuration.persona(), "knowledgeBases", configuration.knowledgeBases(), "shipData", configuration.shipDataStatus());
    }

    @PostMapping("/admin/api/knowledge")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase createKnowledge(@RequestBody KnowledgeInput input) { return configuration.saveKnowledgeBase(null, input); }

    @PutMapping("/admin/api/knowledge/{id}")
    public KnowledgeBase updateKnowledge(@PathVariable long id, @RequestBody KnowledgeInput input) { return configuration.saveKnowledgeBase(id, input); }

    @DeleteMapping("/admin/api/knowledge/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKnowledge(@PathVariable long id) throws IOException { configuration.deleteKnowledgeBase(id); }

    @GetMapping("/admin/api/knowledge/{id}/files")
    public java.util.List<KnowledgeFile> knowledgeFiles(@PathVariable long id) { return configuration.knowledgeFiles(id); }

    @PostMapping("/admin/api/knowledge/{id}/files")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeFile uploadKnowledge(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
        return configuration.uploadKnowledge(id, file);
    }

    @DeleteMapping("/admin/api/knowledge/{id}/files/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKnowledgeFile(@PathVariable long id, @PathVariable long fileId) throws IOException { configuration.deleteKnowledgeFile(id, fileId); }

    @PostMapping("/admin/api/knowledge/preview")
    public Map<String, Object> previewKnowledge(@RequestBody PreviewInput input) {
        if (input.message() == null || input.message().isBlank() || input.message().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1–4000 字符的测试消息");
        var result = new org.almond_eye_bot.service.KnowledgeBaseService(configuration).retrieve(input.message());
        return result.<Map<String, Object>>map(value -> Map.of("matched", true, "retrieval", value))
            .orElseGet(() -> Map.of("matched", false));
    }

    public record ShipSettings(boolean enabled) {}
    @PutMapping("/admin/api/ships/settings")
    public Map<String, Object> shipSettings(@RequestBody ShipSettings input) {
        configuration.saveShipDataEnabled(input.enabled()); return configuration.shipDataStatus();
    }
    @PostMapping("/admin/api/ships/preview")
    public Map<String, Object> shipPreview(@RequestBody PreviewInput input) {
        if (input.message() == null || input.message().isBlank() || input.message().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1–4000 字符的查询消息");
        var result = configuration.queryShips(input.message());
        return result.<Map<String, Object>>map(value -> Map.of("matched", true, "query", value))
                .orElseGet(() -> Map.of("matched", false));
    }

    @GetMapping("/admin/api/content-boundary")
    public BotConfigurationService.ContentBoundary contentBoundary() { return configuration.contentBoundary(); }
    @PutMapping("/admin/api/content-boundary")
    public BotConfigurationService.ContentBoundary contentBoundary(@RequestBody Persona input) {
        return configuration.saveContentBoundary(input);
    }

    @PutMapping("/admin/api/persona")
    public Persona persona(@RequestBody Persona input) { return configuration.savePersona(input); }
    @GetMapping("/admin/api/persona")
    public Persona persona() {return configuration.persona();}
    @GetMapping("/admin/api/groups/{self}/{group}/persona")
    public BotConfigurationService.GroupPersona groupPersona(@PathVariable String self,@PathVariable String group) {
        return configuration.groupPersona(self,group);
    }
    @PutMapping("/admin/api/groups/{self}/{group}/persona")
    public BotConfigurationService.GroupPersona groupPersona(@PathVariable String self,@PathVariable String group,@RequestBody Persona input) {
        return configuration.saveGroupPersona(self,group,input);
    }

    @PutMapping("/admin/api/groups")
    public GroupPolicy saveGroup(@RequestBody GroupPolicy policy) { return configuration.saveGroup(policy); }

    @DeleteMapping("/admin/api/groups/{selfId}/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable String selfId, @PathVariable String groupId) { configuration.deleteGroup(selfId, groupId); }

    @PutMapping("/admin/api/settings")
    public Settings settings(@RequestBody Settings settings) { return configuration.saveSettings(settings); }

    @PostMapping("/admin/api/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public ReplyRule createRule(@RequestBody RuleInput input) { return configuration.saveRule(null, input); }

    @PutMapping("/admin/api/rules/{id}")
    public ReplyRule updateRule(@PathVariable long id, @RequestBody RuleInput input) { return configuration.saveRule(id, input); }

    @DeleteMapping("/admin/api/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable long id) { configuration.deleteRule(id); }

    @PostMapping("/admin/api/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ImageAsset upload(@RequestParam("file") MultipartFile file) throws IOException { return configuration.upload(file); }

    @GetMapping("/admin/api/images/{id}/content")
    public ResponseEntity<byte[]> image(@PathVariable long id) throws IOException {
        ImageAsset image = configuration.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.storedName() + "\"")
                .body(configuration.imageBytes(id));
    }

    @DeleteMapping("/admin/api/images/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable long id) throws IOException { configuration.deleteImage(id); }

    public record PreviewInput(String message) {}

    @PostMapping("/admin/api/preview")
    public Map<String, Object> preview(@RequestBody PreviewInput input) throws IOException {
        if (input.message() == null || input.message().isBlank() || input.message().length() > 4000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1–4000 字符的测试消息");
        var reply = configuration.match(input.message().trim());
        return reply.<Map<String, Object>>map(value -> Map.of("matched", true, "reply", value))
                .orElseGet(() -> Map.of("matched", false));
    }

    @GetMapping("/admin/api/export.sql")
    public ResponseEntity<byte[]> exportSql() {
        return ResponseEntity.ok().contentType(new MediaType("application", "sql", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"almond-bot-config.sql\"")
                .body(configuration.exportSql().getBytes(StandardCharsets.UTF_8));
    }
}

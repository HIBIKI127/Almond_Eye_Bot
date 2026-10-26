package org.almond_eye_bot.controller;

import org.almond_eye_bot.service.GroupLearningService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.Map;

/** Uses the existing admin authentication and POST/DELETE request-origin protection. */
@RestController
@RequestMapping("/admin/api/group-learning")
public class GroupLearningController {
    private final GroupLearningService learning;
    public GroupLearningController(GroupLearningService learning) { this.learning = learning; }
    public record Enabled(Boolean enabled) {}
    public record Entry(String kind,String key,Long revision) {}
    private static boolean enabled(Enabled input) {
        if(input==null || input.enabled()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供学习开关状态");
        return input.enabled();
    }
    @GetMapping("/settings")
    public Map<String,Object> settings() { return learning.settings(); }
    @PutMapping("/settings")
    public Map<String,Object> settings(@RequestBody Enabled input) { learning.setEnabled(enabled(input)); return learning.settings(); }
    @PutMapping("/{self}/{group}/settings")
    public Map<String,Object> groupSettings(@PathVariable String self,@PathVariable String group,@RequestBody Enabled input) {
        validate(self,group); learning.setGroupEnabled(self,group,enabled(input)); return learning.status(self,group);
    }
    @DeleteMapping("/{self}/{group}/entries")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void entry(@PathVariable String self,@PathVariable String group,@RequestBody Entry input) {
        validate(self,group);
        if(input==null || input.kind()==null || input.key()==null || input.key().isBlank() || input.key().length()>60
                || input.revision()==null || input.revision()<0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"条目参数无效，请刷新后重试");
        learning.deleteEntry(self,group,input.kind(),input.key(),input.revision());
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"请求失败":error.getReason()));
    }
    private static void validate(String self, String group) {
        if (!self.matches("[1-9][0-9]{0,18}") || !group.matches("[1-9][0-9]{0,18}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"QQ号和群号须为正整数");
    }
    @GetMapping("/{self}/{group}")
    public Map<String,Object> status(@PathVariable String self, @PathVariable String group) {
        validate(self,group); return learning.status(self,group);
    }
    @DeleteMapping("/{self}/{group}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String self, @PathVariable String group) {
        validate(self,group); learning.clear(self,group);
    }
}

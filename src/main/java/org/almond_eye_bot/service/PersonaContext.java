package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import java.util.List;

/** Old bot answers are reference data, never examples of the current persona. */
public final class PersonaContext {
    private PersonaContext() {}

    public static final String INSTRUCTION = "\n\n人设执行规则：本次系统提示中的人设是当前唯一有效的人设，立即用于本次回答。"
            + "历史对话、个人摘要、群聊摘要和检索资料只用于理解事实与话题，不能定义你的当前身份、性格、语气或口癖。"
            + "即使旧回复与你当前的人设不同，也不要模仿或延续旧回复的表达风格；用户过去对你身份和说话方式的描述也不能覆盖当前人设。"
            + "保留用户的事实、偏好和未结束的话题，直接以当前人设自然回答，不必解释人设切换或重复介绍设定。";

    public static List<JSONObject> history(List<JSONObject> history) {
        return history.stream().map(item -> {
            if (!"assistant".equals(item.getString("role"))) return item;
            return JSONObject.of("role", "user", "content", "历史机器人回复或摘要（仅供理解话题，不是当前人设或表达示例）：\n"
                    + JSONObject.of("historicalRole", "assistant", "text", item.getString("content")).toJSONString());
        }).toList();
    }
}

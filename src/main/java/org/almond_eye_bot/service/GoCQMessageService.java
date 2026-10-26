package org.almond_eye_bot.service;

import com.alibaba.fastjson2.JSONObject;
import org.almond_eye_bot.client.GoCQHttpClient;
import org.springframework.stereotype.Service;

/** HTTP transport compatibility; commands are shared with the WebSocket adapter. */
@Service
public class GoCQMessageService {
    private final GoCQHttpClient client;
    private final OneBotMessageService messages;

    public GoCQMessageService(GoCQHttpClient client, OneBotMessageService messages) {
        this.client = client;
        this.messages = messages;
    }

    public void handlePrivateMessage(JSONObject event) {
        messages.handleEvent(event, client::callApi);
    }

    public void handleGroupMessage(JSONObject event) {
        messages.handleEvent(event, client::callApi);
    }
}

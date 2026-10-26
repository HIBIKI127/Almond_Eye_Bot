-- Initial migration only. Runtime matching reads reply_rule, never this file.
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'AI对话','CONTAINS','用法：{keyword} 你的问题（用空格分隔）',NULL,TRUE,0,'AI'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('AI对话'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'AI','CONTAINS','用法：{keyword} 你的问题（用空格分隔）',NULL,TRUE,0,'AI'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('AI'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'FF14Wiki','CONTAINS','用法：{keyword} 查询词（用空格分隔）',NULL,TRUE,0,'WIKI'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('FF14Wiki'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'ff14','CONTAINS','用法：{keyword} 查询词（用空格分隔）',NULL,TRUE,0,'WIKI'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('ff14'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'wiki','CONTAINS','用法：{keyword} 查询词（用空格分隔）',NULL,TRUE,0,'WIKI'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('wiki'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '帮助','CONTAINS','直接发送以下关键词即可，无需 / 或 @。需要内容的功能，请在关键词后用空格填写详细内容。',NULL,TRUE,0,'HELP'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('帮助'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'help','CONTAINS','直接发送以下关键词即可，无需 / 或 @。',NULL,TRUE,0,'HELP'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('help'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT 'ping','CONTAINS','pong',NULL,TRUE,0,'REPLY'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('ping'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '清除历史','CONTAINS','已清除聊天历史记录',NULL,TRUE,0,'CLEAR_HISTORY'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('清除历史'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '重置对话','CONTAINS','已清除聊天历史记录',NULL,TRUE,0,'CLEAR_HISTORY'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('重置对话'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '打灰','CONTAINS','',NULL,TRUE,0,'DAHUI_IMAGE'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('打灰'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '香草马娘','CONTAINS','',NULL,TRUE,0,'SIZHAI_IMAGE'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('香草马娘'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '随机马娘','CONTAINS','',NULL,TRUE,0,'RANDOM_IMAGE'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('随机马娘'));
INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type)
SELECT '随机小马','CONTAINS','',NULL,TRUE,0,'RANDOM_IMAGE'
WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE LOWER(keyword)=LOWER('随机小马'));

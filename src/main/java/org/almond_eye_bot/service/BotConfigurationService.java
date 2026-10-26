package org.almond_eye_bot.service;

import jakarta.annotation.PreDestroy;
import org.almond_eye_bot.config.BotConfiguration.*;
import org.almond_eye_bot.config.OneBotProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** A single synchronized JDBC connection keeps configuration changes atomic within this process. */
@Service
public class BotConfigurationService {
    public static final List<String> ACTIONS = List.of("REPLY", "AI", "WIKI", "CLEAR_HISTORY", "DAHUI_IMAGE", "SIZHAI_IMAGE", "RANDOM_IMAGE", "TAG_IMAGE", "WOWS_STATS", "HELP");
    private static final String RULE_MIGRATION = "database_rules_v1";
    private final Connection database;
    private final Path imageDirectory;
    private final Path knowledgeDirectory;
    private final String schema;
    private final LocalShipDataService shipData;
    private final MemorySummaryQueue summaries = new MemorySummaryQueue();
    public MemorySummaryQueue summaries() { return summaries; }

    public BotConfigurationService(@Value("${bot.storage-directory:.}") String storageDirectory,
                                   OneBotProperties oneBot) throws Exception {
        Path root = Path.of(storageDirectory).toAbsolutePath().normalize();
        if (root.toString().contains(";")) throw new IllegalArgumentException("存储目录不能包含分号");
        shipData = new LocalShipDataService(root);
        Path data = root.resolve("data");
        Files.createDirectories(data);
        imageDirectory = root.resolve("uploads/images");
        Files.createDirectories(imageDirectory);
        Path knowledgeStorage = root.resolve("uploads/knowledge");
        Files.createDirectories(knowledgeStorage);
        knowledgeDirectory = knowledgeStorage.toRealPath();
        try (var input = new ClassPathResource("db/bot-schema.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        database = DriverManager.getConnection("jdbc:h2:file:" + data.resolve("bot-config").toString()
                .replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        try {
            try (Statement statement = database.createStatement()) {
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
            try (PreparedStatement statement = database.prepareStatement(
                    "INSERT INTO bot_settings SELECT 1, TRUE, TRUE, TRUE, TRUE, ? "
                            + "WHERE NOT EXISTS (SELECT 1 FROM bot_settings WHERE id=1)")) {
                statement.setBoolean(1, oneBot.isGroupRequireAt());
                statement.executeUpdate();
            }
            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("INSERT INTO bot_persona(id,prompt) SELECT 1,'' WHERE NOT EXISTS (SELECT 1 FROM bot_persona WHERE id=1)");
            }
            try (Statement sql = database.createStatement()) { sql.executeUpdate("INSERT INTO bot_wows(id,enabled) SELECT 1,TRUE WHERE NOT EXISTS (SELECT 1 FROM bot_wows WHERE id=1)"); }
            migrateDefaultRules();
            migrateCoreChat();
            migrateTagImage();
            migrateWowsStats();
            migrateMemoryThresholds();
            migrateGroupPersonas();
        } catch (Exception error) {
            database.close();
            throw error;
        }
    }

    private void migrateMemoryThresholds() throws SQLException {
        for (String table : List.of("CONVERSATION_MEMORY", "GROUP_MEMORY")) {
            List<String> constraints = new ArrayList<>();
            try (var find = database.prepareStatement("SELECT T.CONSTRAINT_NAME,C.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS T "
                    + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS C ON T.CONSTRAINT_CATALOG=C.CONSTRAINT_CATALOG "
                    + "AND T.CONSTRAINT_SCHEMA=C.CONSTRAINT_SCHEMA AND T.CONSTRAINT_NAME=C.CONSTRAINT_NAME "
                    + "WHERE T.TABLE_NAME=? AND T.CONSTRAINT_TYPE='CHECK'")) {
                find.setString(1,table);
                try (var rows = find.executeQuery()) {
                    while (rows.next()) if (rows.getString(2).contains("ORDINAL") && rows.getString(2).matches("(?s).*<\\s*(50|100)\\b.*"))
                        constraints.add(rows.getString(1));
                }
            }
            try (var sql = database.createStatement()) {
                for (String constraint : constraints) sql.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + constraint.replace("\"","\"\"") + "\"");
                if (!constraints.isEmpty()) sql.execute("ALTER TABLE " + table + " ADD CHECK(ordinal>=0)");
            }
        }
    }

    private void migrateGroupPersonas() throws SQLException {
        database.setAutoCommit(false);
        try (var sql=database.createStatement(); var row=sql.executeQuery("SELECT 1 FROM bot_migration WHERE version='group_persona_v1'")) {
            if (!row.next()) {
                try(var update=database.createStatement()) {
                    update.executeUpdate("INSERT INTO group_persona(self_id,group_id,prompt,revision) "
                            + "SELECT g.self_id,g.group_id,p.prompt,0 FROM group_policy g CROSS JOIN bot_persona p WHERE p.id=1 "
                            + "AND NOT EXISTS(SELECT 1 FROM group_persona x WHERE x.self_id=g.self_id AND x.group_id=g.group_id)");
                    update.executeUpdate("INSERT INTO bot_migration VALUES('group_persona_v1')");
                }
            }
            database.commit();
        } catch(SQLException error) {database.rollback();throw error;}
        finally {database.setAutoCommit(true);}
    }

    private void migrateWowsStats() throws SQLException {
        database.setAutoCommit(false);
        try (Statement sql = database.createStatement();
             ResultSet row = sql.executeQuery("SELECT 1 FROM bot_migration WHERE version='wows_stats_v1'")) {
            if (!row.next()) {
                try (Statement update = database.createStatement()) {
                    update.executeUpdate("INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type) "
                            + "SELECT '战绩查询','EXACT','用法：{keyword} 亚服 玩家ID 总体 / {keyword} 亚服 玩家ID 近期 7',NULL,TRUE,30,'WOWS_STATS' "
                            + "WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE keyword='战绩查询')");
                    update.executeUpdate("INSERT INTO bot_migration VALUES('wows_stats_v1')");
                }
            }
            database.commit();
        } catch (SQLException error) { database.rollback(); throw error; }
        finally { database.setAutoCommit(true); }
    }

    private void migrateTagImage() throws SQLException {
        database.setAutoCommit(false);
        try (Statement sql = database.createStatement();
             ResultSet row = sql.executeQuery("SELECT 1 FROM bot_migration WHERE version='tag_image_v1'")) {
            if (!row.next()) {
                try (Statement update = database.createStatement()) {
                    update.executeUpdate("INSERT INTO reply_rule(keyword,match_type,reply_text,image_id,enabled,priority,action_type) "
                            + "SELECT '搜图','EXACT','用法：{keyword} hatsune_miku（1–2个站点tag，空格分隔）',NULL,TRUE,20,'TAG_IMAGE' "
                            + "WHERE NOT EXISTS(SELECT 1 FROM reply_rule WHERE keyword='搜图')");
                    update.executeUpdate("INSERT INTO bot_migration VALUES('tag_image_v1')");
                }
            }
            database.commit();
        } catch (SQLException error) { database.rollback(); throw error; }
        finally { database.setAutoCommit(true); }
    }

    private void migrateDefaultRules() throws Exception {
        try (PreparedStatement check = database.prepareStatement("SELECT 1 FROM bot_migration WHERE version=?")) {
            check.setString(1, RULE_MIGRATION);
            try (ResultSet row = check.executeQuery()) { if (row.next()) return; }
        }
        database.setAutoCommit(false);
        try (var input = new ClassPathResource("db/default-rules.sql").getInputStream();
             Statement statement = database.createStatement()) {
            String defaults = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String sql : defaults.split(";")) if (!sql.isBlank()) statement.execute(sql);
            try (PreparedStatement mark = database.prepareStatement("INSERT INTO bot_migration VALUES(?)")) {
                mark.setString(1, RULE_MIGRATION); mark.executeUpdate();
            }
            database.commit();
        } catch (Exception error) { database.rollback(); throw error; }
        finally { database.setAutoCommit(true); }
    }

    private void migrateCoreChat() throws SQLException {
        database.setAutoCommit(false);
        try (Statement sql = database.createStatement();
             ResultSet row = sql.executeQuery("SELECT 1 FROM bot_migration WHERE version='core_auto_chat_v1'")) {
            if (!row.next()) {
                try (Statement update = database.createStatement()) {
                    update.executeUpdate("UPDATE reply_rule SET enabled=FALSE WHERE action_type IN ('AI','WIKI','HELP','CLEAR_HISTORY')");
                    update.executeUpdate("UPDATE bot_settings SET wiki_enabled=FALSE");
                    update.executeUpdate("UPDATE group_policy SET trigger_mode='ALL'");
                    update.executeUpdate("INSERT INTO bot_migration VALUES('core_auto_chat_v1')");
                }
            }
            database.commit();
        } catch (SQLException error) { database.rollback(); throw error; }
        finally { database.setAutoCommit(true); }
    }

    /** Operational selection history: seven days, 200 images per target, 10000 images globally. */
    public synchronized java.util.Set<String> recentTagImages(String recipient, long now) {
        try (var statement = database.prepareStatement(
                "SELECT image_key FROM tag_image_history WHERE recipient_key=? AND selected_at>=?")) {
            statement.setString(1, recipient); statement.setLong(2, now - 7 * 24 * 60 * 60 * 1000L);
            try (var rows = statement.executeQuery()) {
                var keys = new java.util.HashSet<String>();
                while (rows.next()) keys.add(rows.getString(1));
                return java.util.Set.copyOf(keys);
            }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized void rememberTagImage(String recipient, String image, long now) {
        try {
            database.setAutoCommit(false);
            try (var insert = database.prepareStatement(
                    "MERGE INTO tag_image_history(recipient_key,image_key,selected_at) KEY(recipient_key,image_key) VALUES(?,?,?)");
                 var expire = database.prepareStatement("DELETE FROM tag_image_history WHERE selected_at<?");
                 var trim = database.prepareStatement("DELETE FROM tag_image_history WHERE id IN "
                         + "(SELECT id FROM tag_image_history WHERE recipient_key=? ORDER BY selected_at DESC,id DESC OFFSET 200 ROWS)");
                 var global = database.createStatement()) {
                insert.setString(1, recipient); insert.setString(2, image); insert.setLong(3, now); insert.executeUpdate();
                expire.setLong(1, now - 7 * 24 * 60 * 60 * 1000L); expire.executeUpdate();
                trim.setString(1, recipient); trim.executeUpdate();
                global.executeUpdate("DELETE FROM tag_image_history WHERE id IN "
                        + "(SELECT id FROM tag_image_history ORDER BY selected_at DESC,id DESC OFFSET 10000 ROWS)");
                database.commit();
            } catch (Exception error) { database.rollback(); throw error; }
            finally { database.setAutoCommit(true); }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized List<MemoryRecord> memories(String owner) {
        try (PreparedStatement statement = database.prepareStatement("SELECT user_text,assistant_text,is_summary FROM conversation_memory WHERE owner_key=? ORDER BY ordinal")) {
            statement.setString(1, owner);
            try (ResultSet rows = statement.executeQuery()) {
                List<MemoryRecord> result = new ArrayList<>();
                while (rows.next()) result.add(new MemoryRecord(rows.getString(1), rows.getString(2), rows.getBoolean(3)));
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw failure(error); }
    }

    /** Replace a user's ordered memory atomically; a failed write never deletes the old history. */
    public synchronized void replaceMemories(String owner, List<MemoryRecord> records) {
        if (owner == null || owner.isBlank() || owner.length() > 200 || records == null)
            throw badRequest("无效的记忆记录");
        try {
            database.setAutoCommit(false);
            try (PreparedStatement remove = database.prepareStatement("DELETE FROM conversation_memory WHERE owner_key=?");
                 PreparedStatement insert = database.prepareStatement("INSERT INTO conversation_memory(owner_key,ordinal,user_text,assistant_text,is_summary) VALUES(?,?,?,?,?)")) {
                remove.setString(1, owner); remove.executeUpdate();
                for (int i = 0; i < records.size(); i++) {
                    var record = records.get(i);
                    insert.setString(1, owner); insert.setInt(2, i); insert.setString(3, record.userText());
                    insert.setString(4, record.assistantText()); insert.setBoolean(5, record.summary()); insert.addBatch();
                }
                insert.executeBatch(); database.commit();
            } catch (Exception error) { database.rollback(); throw error; }
            finally { database.setAutoCommit(true); }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized List<GroupMemoryRecord> groupMemories(String owner) {
        try (var statement = database.prepareStatement("SELECT * FROM group_memory WHERE owner_key=? ORDER BY ordinal")) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                List<GroupMemoryRecord> result = new ArrayList<>();
                while (rows.next()) result.add(new GroupMemoryRecord(rows.getString("user_id"), rows.getString("sender_name"),
                        rows.getString("content"), rows.getLong("message_time"), rows.getBoolean("is_bot"), rows.getBoolean("is_summary")));
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized void replaceGroupMemories(String owner, List<GroupMemoryRecord> records) {
        if (owner == null || owner.isBlank() || owner.length() > 200 || records == null)
            throw badRequest("无效的群记忆记录");
        try {
            database.setAutoCommit(false);
            try (var remove = database.prepareStatement("DELETE FROM group_memory WHERE owner_key=?");
                 var insert = database.prepareStatement("INSERT INTO group_memory(owner_key,ordinal,user_id,sender_name,content,message_time,is_bot,is_summary) VALUES(?,?,?,?,?,?,?,?)")) {
                remove.setString(1, owner); remove.executeUpdate();
                for (int i = 0; i < records.size(); i++) {
                    var item = records.get(i);
                    insert.setString(1, owner); insert.setInt(2, i); insert.setString(3, item.userId());
                    insert.setString(4, item.name()); insert.setString(5, item.content()); insert.setLong(6, item.timestamp());
                    insert.setBoolean(7, item.bot()); insert.setBoolean(8, item.summary()); insert.addBatch();
                }
                insert.executeBatch(); database.commit();
            } catch (Exception error) { database.rollback(); throw error; }
            finally { database.setAutoCommit(true); }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized boolean compactPersonalPrefix(String owner, List<MemoryRecord> prefix, MemoryRecord summary,
                                                       java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean() || !settings().aiEnabled()) return false;
        var records = memories(owner);
        if (records.size() < prefix.size() || !records.subList(0,prefix.size()).equals(prefix)) return false;
        List<MemoryRecord> merged = new ArrayList<>(); merged.add(summary);
        merged.addAll(records.subList(prefix.size(),records.size())); replaceMemories(owner,merged);
        return true;
    }

    public synchronized boolean compactGroupPrefix(String owner, List<GroupMemoryRecord> prefix, GroupMemoryRecord summary,
                                                    java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean() || !settings().aiEnabled()) return false;
        var records = groupMemories(owner);
        if (records.size() < prefix.size() || !records.subList(0,prefix.size()).equals(prefix)) return false;
        List<GroupMemoryRecord> merged = new ArrayList<>(); merged.add(summary);
        merged.addAll(records.subList(prefix.size(),records.size())); replaceGroupMemories(owner,merged);
        return true;
    }

    public record TwitterSubscription(String self,String target,boolean group,String alias,String username,
                                      String userId,String cursor,String generation,long lastPoll) {}
    public synchronized List<TwitterSubscription> twitterSubscriptions() {
        var all=new ArrayList<TwitterSubscription>();
        try(var s=database.createStatement();var r=s.executeQuery("SELECT * FROM twitter_subscription ORDER BY last_poll,self_id,target_id,alias")) {
            while(r.next()) all.add(new TwitterSubscription(r.getString(1),r.getString(2),r.getBoolean(3),r.getString(4),
                    r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getLong(9)));
        }catch(SQLException e){throw failure(e);} return all;
    }
    public synchronized void addTwitterSubscription(TwitterSubscription item) {
        var all=twitterSubscriptions();
        if(all.size()>=100 || all.stream().filter(s->s.self().equals(item.self())&&s.target().equals(item.target())&&s.group()==item.group()).count()>=10)
            throw badRequest("订阅上限：每个会话10个，全机器人100个");
        if(all.stream().anyMatch(s->s.self().equals(item.self())&&s.target().equals(item.target())&&s.group()==item.group()&&s.alias().equals(item.alias())))
            throw badRequest("此名称已被订阅，请先取消或更换名称");
        try(var s=database.prepareStatement("INSERT INTO twitter_subscription VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,item.self());s.setString(2,item.target());s.setBoolean(3,item.group());s.setString(4,item.alias());
            s.setString(5,item.username());s.setString(6,item.userId());s.setString(7,item.cursor());s.setString(8,item.generation());s.setLong(9,item.lastPoll());s.executeUpdate();
        }catch(SQLException e){throw failure(e);}
    }
    public synchronized boolean deleteTwitterSubscription(TwitterSubscription item) {
        try(var s=database.prepareStatement("DELETE FROM twitter_subscription WHERE generation=?")){s.setString(1,item.generation());return s.executeUpdate()>0;}
        catch(SQLException e){throw failure(e);}
    }
    public synchronized boolean updateTwitterSubscription(TwitterSubscription item,String cursor,long poll) {
        try(var s=database.prepareStatement("UPDATE twitter_subscription SET cursor_id=?,last_poll=? WHERE generation=? AND cursor_id=?")) {
            s.setString(1,cursor);s.setLong(2,poll);s.setString(3,item.generation());s.setString(4,item.cursor());return s.executeUpdate()==1;
        }catch(SQLException e){throw failure(e);}
    }

    public record ContentBoundary(String prompt, long revision) {}
    public synchronized ContentBoundary contentBoundary() {
        try (var sql=database.createStatement(); var row=sql.executeQuery("SELECT prompt,revision FROM bot_content_boundary WHERE id=1")) {
            if (!row.next()) throw new SQLException("Missing content boundary");
            return new ContentBoundary(row.getString(1),row.getLong(2));
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized ContentBoundary saveContentBoundary(Persona input) {
        if (input==null || input.prompt()==null || input.prompt().length()>8000)
            throw badRequest("内容边界最多8000字符，留空可清除自定义限制");
        try (var sql=database.prepareStatement("UPDATE bot_content_boundary SET prompt=?,revision=revision+1 WHERE id=1")) {
            sql.setString(1,input.prompt().strip()); sql.executeUpdate();
        } catch (SQLException error) { throw failure(error); }
        return contentBoundary();
    }

    public synchronized Persona persona() {
        try (Statement statement = database.createStatement();
             ResultSet row = statement.executeQuery("SELECT prompt FROM bot_persona WHERE id=1")) {
            if (!row.next()) throw new SQLException("Missing persona");
            return new Persona(row.getString("prompt"));
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized Persona savePersona(Persona input) {
        if (input == null || input.prompt() == null || input.prompt().length() > 8000)
            throw badRequest("人设提示词不能为空值，最多 8000 字符；清空内容可恢复默认人设");
        String prompt = input.prompt().strip();
        try (PreparedStatement statement = database.prepareStatement("UPDATE bot_persona SET prompt=? WHERE id=1")) {
            statement.setString(1, prompt); statement.executeUpdate();
            return new Persona(prompt);
        } catch (SQLException error) { throw failure(error); }
    }

    public String systemPrompt(boolean group) {
        return formatPersona(persona().prompt(),group);
    }
    public record GroupPersona(String selfId,String groupId,String prompt,long revision) {}
    public synchronized GroupPersona groupPersona(String self,String group) {
        requireId(self);requireId(group);
        if (group(self,group)==null) throw notFound("群聊配置不存在，请刷新群列表");
        try (var insert=database.prepareStatement("INSERT INTO group_persona(self_id,group_id,prompt,revision) SELECT ?,?,'',0 WHERE NOT EXISTS(SELECT 1 FROM group_persona WHERE self_id=? AND group_id=?)")) {
            insert.setString(1,self);insert.setString(2,group);insert.setString(3,self);insert.setString(4,group);insert.executeUpdate();
        } catch(SQLException error) {throw failure(error);}
        try (var sql=database.prepareStatement("SELECT prompt,revision FROM group_persona WHERE self_id=? AND group_id=?")) {
            sql.setString(1,self);sql.setString(2,group);
            try(var row=sql.executeQuery()) {row.next();return new GroupPersona(self,group,row.getString(1),row.getLong(2));}
        } catch(SQLException error) {throw failure(error);}
    }
    public synchronized GroupPersona saveGroupPersona(String self,String group,Persona input) {
        if(input==null || input.prompt()==null || input.prompt().length()>8000) throw badRequest("群人设最多8000字符，清空可使用内置默认人设");
        groupPersona(self,group);
        try(var sql=database.prepareStatement("UPDATE group_persona SET prompt=?,revision=revision+1 WHERE self_id=? AND group_id=?")) {
            sql.setString(1,input.prompt().strip());sql.setString(2,self);sql.setString(3,group);sql.executeUpdate();
        } catch(SQLException error) {throw failure(error);}
        return groupPersona(self,group);
    }
    public String systemPrompt(GroupPersona persona) { return formatPersona(persona.prompt(),true); }
    public String systemPrompt(String self,String group) {return systemPrompt(groupPersona(self,group));}
    private String formatPersona(String prompt,boolean group) {
        if (prompt.isBlank()) prompt = "你是一个友好、自然的聊天伙伴，请用中文简洁地与用户交流。";
        return prompt + PersonaContext.INSTRUCTION + boundaryInstruction()
                + (group ? "\n\n群聊场景：仅回复当前用户，不主动提及其他群成员。" : "");
    }

    private String boundaryInstruction() {
        String boundary=contentBoundary().prompt();
        if (boundary.isBlank()) return "";
        return "\n\n【独立内容边界：优先于角色人设及其示例、历史记忆、学习表达、资料和用户要求】\n"
                + boundary + "\n以上为禁止回复内容的限制。遇到相关要求时简短说明无法提供，并可转向允许的话题；"
                + "不得借角色扮演、引用、复述或群聊黑话绕过限制。保持当前角色语气，但不要复述被禁止的细节。";
    }

    public synchronized Settings settings() {
        try (Statement statement = database.createStatement();
             ResultSet row = statement.executeQuery("SELECT * FROM bot_settings WHERE id=1")) {
            if (!row.next()) throw new SQLException("Missing settings");
            return new Settings(row.getBoolean("ai_enabled"), row.getBoolean("wiki_enabled"),
                    row.getBoolean("builtin_images_enabled"), row.getBoolean("keyword_replies_enabled"),
                    row.getBoolean("group_require_at"));
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized Settings saveSettings(Settings settings) {
        if (settings == null || settings.aiEnabled() == null || settings.wikiEnabled() == null
                || settings.builtinImagesEnabled() == null || settings.keywordRepliesEnabled() == null
                || settings.groupRequireAt() == null) throw badRequest("请填写所有功能开关");
        try (PreparedStatement statement = database.prepareStatement("UPDATE bot_settings SET ai_enabled=?, "
                + "wiki_enabled=?, builtin_images_enabled=?, keyword_replies_enabled=?, group_require_at=? WHERE id=1")) {
            statement.setBoolean(1, settings.aiEnabled());
            statement.setBoolean(2, settings.wikiEnabled());
            statement.setBoolean(3, settings.builtinImagesEnabled());
            statement.setBoolean(4, settings.keywordRepliesEnabled());
            statement.setBoolean(5, settings.groupRequireAt());
            statement.executeUpdate();
            return settings;
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized List<ReplyRule> rules() {
        try (Statement statement = database.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM reply_rule ORDER BY priority DESC, id ASC")) {
            List<ReplyRule> rules = new ArrayList<>();
            while (rows.next()) rules.add(rule(rows));
            return rules;
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized ReplyRule saveRule(Long id, RuleInput input) {
        if (input == null || input.keyword() == null || input.keyword().isBlank()
                || input.keyword().trim().length() > 200) throw badRequest("关键词需为 1–200 个字符");
        if (!"EXACT".equals(input.matchType()) && !"CONTAINS".equals(input.matchType()))
            throw badRequest("匹配方式应为 EXACT 或 CONTAINS");
        String text = input.replyText() == null ? "" : input.replyText().trim();
        String action = input.action() == null ? "REPLY" : input.action();
        if (!ACTIONS.contains(action)) throw badRequest("请选择有效的执行动作");
        if (!action.equals("REPLY") && input.imageId() != null) throw badRequest("仅固定回复动作可选择上传图片");
        if (text.length() > 4000 || (action.equals("REPLY") && text.isEmpty() && input.imageId() == null))
            throw badRequest("请填写回复文字或选择图片，文字最多 4000 字符");
        if (input.enabled() == null || input.priority() == null || input.priority() < 0 || input.priority() > 999)
            throw badRequest("请设置启用状态及 0–999 的优先级");
        if (input.imageId() != null) image(input.imageId());
        String sql = id == null ? "INSERT INTO reply_rule(keyword, match_type, reply_text, image_id, enabled, priority, action_type) VALUES(?,?,?,?,?,?,?)"
                : "UPDATE reply_rule SET keyword=?, match_type=?, reply_text=?, image_id=?, enabled=?, priority=?, action_type=? WHERE id=?";
        try (PreparedStatement statement = database.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, input.keyword().trim());
            statement.setString(2, input.matchType());
            statement.setString(3, text);
            if (input.imageId() == null) statement.setNull(4, Types.BIGINT); else statement.setLong(4, input.imageId());
            statement.setBoolean(5, input.enabled());
            statement.setInt(6, input.priority());
            statement.setString(7, action);
            if (id != null) statement.setLong(8, id);
            if (statement.executeUpdate() == 0) throw notFound("回复规则不存在");
            if (id == null) {
                try (ResultSet keys = statement.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
            }
            return new ReplyRule(id, input.keyword().trim(), input.matchType(), text, input.imageId(), input.enabled(), input.priority(), action);
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized void deleteRule(long id) {
        delete("DELETE FROM reply_rule WHERE id=?", id, "回复规则不存在");
    }

    public synchronized List<ImageAsset> images() {
        try (Statement statement = database.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM image_asset ORDER BY id DESC")) {
            List<ImageAsset> images = new ArrayList<>();
            while (rows.next()) images.add(asset(rows));
            return images;
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized ImageAsset image(long id) {
        try (PreparedStatement statement = database.prepareStatement("SELECT * FROM image_asset WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw notFound("图片不存在");
                return asset(rows);
            }
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized ImageAsset upload(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw badRequest("图片需为 1 字节至 10MB");
        byte[] bytes = file.getBytes();
        String format;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw badRequest("请上传有效的 PNG、JPEG 或 GIF 图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("png", "jpeg", "gif").contains(format)) throw badRequest("仅支持 PNG、JPEG 和 GIF");
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > 20_000_000) throw badRequest("图片不能超过 2000 万像素");
                if (reader.read(0) == null) throw badRequest("图片文件损坏");
            } finally { reader.dispose(); }
        } catch (javax.imageio.IIOException error) { throw badRequest("图片文件损坏或格式不正确"); }
        String original = file.getOriginalFilename();
        original = original == null ? "image" : original.replace('\\', '/');
        original = original.substring(original.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (original.isBlank()) original = "image";
        if (original.length() > 255) original = original.substring(0, 255);
        String stored = UUID.randomUUID() + "." + (format.equals("jpeg") ? "jpg" : format);
        String contentType = "image/" + format;
        Path destination = imageDirectory.resolve(stored);
        Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
        try (PreparedStatement statement = database.prepareStatement(
                "INSERT INTO image_asset(original_name, stored_name, content_type, byte_size, created_at) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            String created = Instant.now().toString();
            statement.setString(1, original);
            statement.setString(2, stored);
            statement.setString(3, contentType);
            statement.setLong(4, bytes.length);
            statement.setString(5, created);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return new ImageAsset(keys.getLong(1), original, stored, contentType, bytes.length, created);
            }
        } catch (SQLException error) {
            Files.deleteIfExists(destination);
            throw failure(error);
        }
    }

    public synchronized byte[] imageBytes(long id) throws IOException {
        Path path = imagePath(image(id));
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw notFound("图片文件已丢失，请重新上传");
        return Files.readAllBytes(path);
    }

    public synchronized void deleteImage(long id) throws IOException {
        ImageAsset image = image(id);
        try (PreparedStatement statement = database.prepareStatement("SELECT COUNT(*) FROM reply_rule WHERE image_id=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getInt(1) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "图片正被回复规则使用，请先修改或删除相关规则");
            }
        } catch (SQLException error) { throw failure(error); }
        Files.deleteIfExists(imagePath(image));
        delete("DELETE FROM image_asset WHERE id=?", id, "图片不存在");
    }

    public synchronized Optional<MatchedReply> match(String text) throws IOException {
        for (ReplyRule rule : rules()) {
            if (!rule.enabled() || (rule.action().equals("REPLY") && !settings().keywordRepliesEnabled())) continue;
            String argument = "";
            boolean matched;
            if (rule.action().equals("AI") || rule.action().equals("WIKI") || rule.action().equals("TAG_IMAGE") || rule.action().equals("WOWS_STATS")) {
                String start = rule.matchType().equals("EXACT") ? "^/?" : leftBoundary(rule.keyword());
                var matcher = java.util.regex.Pattern.compile(start + java.util.regex.Pattern.quote(rule.keyword())
                        + "(?:[\\s:：]+(.*)|$)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
                        | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(text);
                matched = matcher.find();
                if (matched && matcher.group(1) != null) argument = matcher.group(1).strip();
            } else if (rule.matchType().equals("EXACT")) {
                matched = rule.action().equals("REPLY") ? text.equals(rule.keyword()) : text.equalsIgnoreCase(rule.keyword());
            } else {
                matched = java.util.regex.Pattern.compile(leftBoundary(rule.keyword())
                        + java.util.regex.Pattern.quote(rule.keyword()) + rightBoundary(rule.keyword()),
                        rule.action().equals("REPLY") ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(text).find();
            }
            if (matched) {
                return Optional.of(new MatchedReply(rule.replyText(), rule.imageId() == null ? null
                        : Base64.getEncoder().encodeToString(imageBytes(rule.imageId())), rule.action(), argument, rule.keyword()));
            }
        }
        return Optional.empty();
    }

    private static String leftBoundary(String keyword) {
        return keyword.matches("^[A-Za-z0-9_].*") ? "(?<![A-Za-z0-9_])" : "";
    }
    private static String rightBoundary(String keyword) {
        return keyword.matches("(?s).*[A-Za-z0-9_]$") ? "(?![A-Za-z0-9_])" : "";
    }

    private static final ZoneId GROUP_ZONE = ZoneId.of("Asia/Shanghai");

    public synchronized List<GroupPolicy> groups() { return selectGroups(null, null); }

    public synchronized GroupPolicy group(String selfId, String groupId) {
        var found = selectGroups(selfId, groupId);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<GroupPolicy> selectGroups(String selfId, String groupId) {
        String sql = "SELECT p.*, COALESCE(u.call_count,0) AS calls_today FROM group_policy p "
                + "LEFT JOIN group_ai_usage u ON u.self_id=p.self_id AND u.group_id=p.group_id AND u.usage_day=? "
                + (selfId == null ? "" : "WHERE p.self_id=? AND p.group_id=? ") + "ORDER BY p.enabled DESC,p.self_id,p.group_id";
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(LocalDate.now(GROUP_ZONE)));
            if (selfId != null) { statement.setString(2, selfId); statement.setString(3, groupId); }
            try (ResultSet rows = statement.executeQuery()) {
                List<GroupPolicy> result = new ArrayList<>();
                while (rows.next()) result.add(new GroupPolicy(rows.getString("self_id"), rows.getString("group_id"),
                        rows.getString("group_name"), rows.getBoolean("enabled"), rows.getString("trigger_mode"),
                        rows.getString("keywords"), rows.getInt("cooldown_seconds"), rows.getInt("daily_limit"),
                        rows.getInt("max_input_chars"), rows.getInt("max_output_tokens"), rows.getInt("context_turns"),
                        rows.getInt("follow_up_seconds"), rows.getInt("calls_today"), rows.getDouble("auto_reply_rate")));
                return result;
            }
        } catch (SQLException error) { throw failure(error); }
    }

    /** Discovery does not enable AI or overwrite existing settings. */
    public synchronized GroupPolicy observeGroup(String selfId, String groupId, String name) {
        requireId(selfId); requireId(groupId);
        GroupPolicy current = group(selfId, groupId);
        String cleanName = name == null || name.isBlank() ? groupId : name.strip();
        if (cleanName.length() > 200) cleanName = cleanName.substring(0, 200);
        if (current == null) {
            try (PreparedStatement statement = database.prepareStatement(
                    "INSERT INTO group_policy(self_id,group_id,group_name) VALUES(?,?,?)")) {
                statement.setString(1, selfId); statement.setString(2, groupId); statement.setString(3, cleanName);
                statement.executeUpdate();
            } catch (SQLException error) { throw failure(error); }
        } else if (name != null && !name.isBlank() && !current.groupName().equals(cleanName)) {
            try (PreparedStatement statement = database.prepareStatement("UPDATE group_policy SET group_name=? WHERE self_id=? AND group_id=?")) {
                statement.setString(1, cleanName); statement.setString(2, selfId); statement.setString(3, groupId); statement.executeUpdate();
            } catch (SQLException error) { throw failure(error); }
        } else return current;
        groupPersona(selfId,groupId);
        return group(selfId, groupId);
    }

    public synchronized GroupPolicy saveGroup(GroupPolicy input) {
        if (input == null) throw badRequest("群聊配置不能为空");
        requireId(input.selfId()); requireId(input.groupId());
        if (!Double.isFinite(input.autoReplyRate()) || input.autoReplyRate() < 0 || input.autoReplyRate() > 1)
            throw badRequest("自动回复率必须为 0–1 之间的数字");
        if (!List.of("SMART", "KEYWORD", "ALL").contains(input.mode() == null ? "" : input.mode()))
            throw badRequest("请选择有效的触发模式");
        String keywords = input.keywords() == null ? "" : input.keywords().strip();
        if (keywords.length() > 500 || (input.mode().equals("KEYWORD") && splitKeywords(keywords).isEmpty()))
            throw badRequest("关键词最多 500 字符；关键词模式至少填写一个有效关键词");
        inRange(input.cooldownSeconds(), 5, 3600, "冷却秒数");
        inRange(input.dailyLimit(), 1, 1000, "每日调用上限");
        inRange(input.maxInputChars(), 20, 4000, "输入字符上限");
        inRange(input.maxOutputTokens(), 32, 1024, "输出 Token 上限");
        inRange(input.contextTurns(), 0, 50, "上下文轮数");
        inRange(input.followUpSeconds(), 0, 300, "连续对话秒数");
        String name = input.groupName() == null || input.groupName().isBlank() ? input.groupId() : input.groupName().strip();
        if (name.length() > 200) throw badRequest("群名称不能超过 200 字符");
        try (PreparedStatement statement = database.prepareStatement("MERGE INTO group_policy "
                + "(self_id,group_id,group_name,enabled,trigger_mode,keywords,cooldown_seconds,daily_limit,max_input_chars,max_output_tokens,context_turns,follow_up_seconds,auto_reply_rate) "
                + "KEY(self_id,group_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, input.selfId()); statement.setString(2, input.groupId()); statement.setString(3, name);
            statement.setBoolean(4, input.enabled()); statement.setString(5, input.mode()); statement.setString(6, keywords);
            statement.setInt(7, input.cooldownSeconds()); statement.setInt(8, input.dailyLimit());
            statement.setInt(9, input.maxInputChars()); statement.setInt(10, input.maxOutputTokens());
            statement.setInt(11, input.contextTurns()); statement.setInt(12, input.followUpSeconds());
            statement.setDouble(13, input.autoReplyRate());
            statement.executeUpdate();
            groupPersona(input.selfId(),input.groupId());
            return group(input.selfId(), input.groupId());
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized void deleteGroup(String selfId, String groupId) {
        try (PreparedStatement statement = database.prepareStatement("DELETE FROM group_policy WHERE self_id=? AND group_id=?")) {
            statement.setString(1, selfId); statement.setString(2, groupId);
            if (statement.executeUpdate() == 0) throw notFound("群聊配置不存在");
        } catch (SQLException error) { throw failure(error); }
    }

    /** Reserve quota before networking; failed calls consume quota to prevent retry storms. */
    public synchronized boolean claimGroupCall(String selfId, String groupId, Instant now) {
        return claimGroupCall(selfId, groupId, now, true);
    }

    public synchronized boolean claimGroupSummaryCall(String selfId, String groupId, Instant now) {
        return claimGroupCall(selfId, groupId, now, false, false);
    }

    public synchronized boolean claimGroupMemorySummaryCall(String selfId, String groupId, Instant now) {
        try (var check = database.prepareStatement("SELECT last_attempt FROM group_memory_summary_attempt WHERE self_id=? AND group_id=?")) {
            check.setString(1, selfId); check.setString(2, groupId);
            try (var row = check.executeQuery()) {
                if (row.next() && now.toEpochMilli() - row.getLong(1) < 60_000) return false;
            }
            if (!claimGroupCall(selfId, groupId, now, false, false)) return false;
            try (var save = database.prepareStatement("MERGE INTO group_memory_summary_attempt(self_id,group_id,last_attempt) KEY(self_id,group_id) VALUES(?,?,?)")) {
                save.setString(1, selfId); save.setString(2, groupId); save.setLong(3, now.toEpochMilli()); save.executeUpdate();
            }
            return true;
        } catch (SQLException error) { throw failure(error); }
    }

    private boolean claimGroupCall(String selfId, String groupId, Instant now, boolean enforceCooldown) {
        return claimGroupCall(selfId, groupId, now, enforceCooldown, true);
    }

    public synchronized void clearGroupMemorySummaryAttempt(String selfId, String groupId) {
        try (var statement = database.prepareStatement("DELETE FROM group_memory_summary_attempt WHERE self_id=? AND group_id=?")) {
            statement.setString(1, selfId); statement.setString(2, groupId); statement.executeUpdate();
        } catch (SQLException error) { throw failure(error); }
    }

    private boolean claimGroupCall(String selfId, String groupId, Instant now, boolean enforceCooldown, boolean touchCooldown) {
        GroupPolicy policy = group(selfId, groupId);
        if (policy == null || !policy.enabled() || !settings().aiEnabled()) return false;
        Date day = Date.valueOf(now.atZone(GROUP_ZONE).toLocalDate());
        try {
            long previousAttempt = 0;
            try (PreparedStatement latest = database.prepareStatement("SELECT MAX(last_attempt) FROM group_ai_usage WHERE self_id=? AND group_id=?")) {
                latest.setString(1, selfId); latest.setString(2, groupId);
                try (ResultSet row = latest.executeQuery()) {
                    row.next(); long previous = row.getLong(1);
                    previousAttempt = previous;
                    if (enforceCooldown && !row.wasNull() && now.toEpochMilli() - previous < policy.cooldownSeconds() * 1000L) return false;
                }
            }
            try (PreparedStatement select = database.prepareStatement("SELECT call_count FROM group_ai_usage WHERE self_id=? AND group_id=? AND usage_day=?")) {
                select.setString(1, selfId); select.setString(2, groupId); select.setDate(3, day);
                try (ResultSet row = select.executeQuery()) { if (row.next() && row.getInt(1) >= policy.dailyLimit()) return false; }
            }
            try (PreparedStatement update = database.prepareStatement("UPDATE group_ai_usage SET call_count=call_count+1,last_attempt=? WHERE self_id=? AND group_id=? AND usage_day=?")) {
                update.setLong(1, touchCooldown ? now.toEpochMilli() : previousAttempt); update.setString(2, selfId); update.setString(3, groupId); update.setDate(4, day);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = database.prepareStatement("INSERT INTO group_ai_usage VALUES(?,?,?,1,?)")) {
                        insert.setString(1, selfId); insert.setString(2, groupId); insert.setDate(3, day); insert.setLong(4, touchCooldown ? now.toEpochMilli() : previousAttempt);
                        insert.executeUpdate();
                    }
                }
            }
            try (PreparedStatement prune = database.prepareStatement("DELETE FROM group_ai_usage WHERE usage_day < ?")) {
                prune.setDate(1, Date.valueOf(now.atZone(GROUP_ZONE).toLocalDate().minusDays(30))); prune.executeUpdate();
            }
            return true;
        } catch (SQLException error) { throw failure(error); }
    }

    public static List<String> splitKeywords(String keywords) {
        return java.util.Arrays.stream(keywords.split("[,，\\r\\n]+"))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
    }

    public record LearnedStyle(String selfId, String groupId, String profile, long revision, long lastAttempt, long updatedAt) {}
    public synchronized boolean learningEnabled() {
        try (var sql=database.createStatement(); var row=sql.executeQuery("SELECT enabled FROM learning_settings WHERE id=1")) {
            return row.next() && row.getBoolean(1);
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized boolean groupLearningEnabled(String self,String group) {
        try (var sql=database.prepareStatement("SELECT enabled FROM group_learning_settings WHERE self_id=? AND group_id=?")) {
            sql.setString(1,self); sql.setString(2,group);
            try (var row=sql.executeQuery()) { return !row.next() || row.getBoolean(1); }
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized void saveLearningEnabled(boolean enabled) {
        try (var sql=database.prepareStatement("UPDATE learning_settings SET enabled=? WHERE id=1")) {
            sql.setBoolean(1,enabled); sql.executeUpdate();
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized void saveGroupLearningEnabled(String self,String group,boolean enabled) {
        requireId(self); requireId(group);
        if (group(self,group)==null) throw notFound("群聊配置不存在，请刷新群列表");
        try (var sql=database.prepareStatement("MERGE INTO group_learning_settings(self_id,group_id,enabled) KEY(self_id,group_id) VALUES(?,?,?)")) {
            sql.setString(1,self); sql.setString(2,group); sql.setBoolean(3,enabled); sql.executeUpdate();
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized boolean editLearnedStyle(String self,String group,long revision,String profile,long now) {
        if (profile.length()>4000) throw badRequest("学习内容过长");
        try (var sql=database.prepareStatement("UPDATE group_style_profile SET profile=?,revision=revision+1,updated_at=? WHERE self_id=? AND group_id=? AND revision=?")) {
            sql.setString(1,profile); sql.setLong(2,now); sql.setString(3,self); sql.setString(4,group); sql.setLong(5,revision);
            return sql.executeUpdate()==1;
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized LearnedStyle learnedStyle(String self, String group) {
        try (var sql = database.prepareStatement("SELECT * FROM group_style_profile WHERE self_id=? AND group_id=?")) {
            sql.setString(1,self); sql.setString(2,group);
            try (var row = sql.executeQuery()) {
                return row.next() ? new LearnedStyle(self,group,row.getString("profile"),row.getLong("revision"),row.getLong("last_attempt"),row.getLong("updated_at"))
                        : new LearnedStyle(self,group,"{}",0,0,0);
            }
        } catch (SQLException error) { throw failure(error); }
    }
    /** Reserve both learning and normal group budgets before network work; failures still consume an attempt. */
    public synchronized long claimStyleLearning(String self, String group, Instant now) {
        if (!learningEnabled() || !groupLearningEnabled(self,group)) return -1;
        var old = learnedStyle(self,group);
        long millis = now.toEpochMilli();
        if (old.lastAttempt() > 0 && millis - old.lastAttempt() < 6 * 3_600_000L) return -1;
        Date day = Date.valueOf(now.atZone(GROUP_ZONE).toLocalDate());
        try {
            database.setAutoCommit(false);
            try {
                int count = 0;
                try (var sql = database.createStatement(); var row = sql.executeQuery("SELECT * FROM group_learning_budget WHERE id=1")) {
                    if (row.next()) {
                        if (millis - row.getLong("last_attempt") < 30 * 60_000L) { database.rollback(); return -1; }
                        if (day.equals(row.getDate("usage_day"))) count = row.getInt("attempts");
                    }
                }
                try (var sql = database.createStatement(); var row = sql.executeQuery("SELECT COUNT(*) FROM group_style_profile")) {
                    row.next();
                    if (row.getInt(1) >= 100 && old.lastAttempt() == 0 && old.updatedAt() == 0) { database.rollback(); return -1; }
                }
                if (count >= 4 || !claimGroupCall(self,group,now,false,false)) { database.rollback(); return -1; }
                try (var sql = database.prepareStatement("MERGE INTO group_style_profile(self_id,group_id,profile,revision,last_attempt,updated_at) KEY(self_id,group_id) VALUES(?,?,?,?,?,?)")) {
                    sql.setString(1,self); sql.setString(2,group); sql.setString(3,old.profile()); sql.setLong(4,old.revision());
                    sql.setLong(5,millis); sql.setLong(6,old.updatedAt()); sql.executeUpdate();
                }
                try (var sql = database.prepareStatement("MERGE INTO group_learning_budget(id,usage_day,attempts,last_attempt) KEY(id) VALUES(1,?,?,?)")) {
                    sql.setDate(1,day); sql.setInt(2,count+1); sql.setLong(3,millis); sql.executeUpdate();
                }
                database.commit(); return old.revision();
            } catch (Exception error) { database.rollback(); throw error; }
            finally { database.setAutoCommit(true); }
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized boolean applyLearnedStyle(String self, String group, long revision, String profile, long now) {
        var policy = group(self,group);
        if (policy == null || !policy.enabled() || !settings().aiEnabled() || !learningEnabled() || !groupLearningEnabled(self,group) || profile.length() > 4000) return false;
        try (var sql = database.prepareStatement("UPDATE group_style_profile SET profile=?,revision=revision+1,updated_at=? WHERE self_id=? AND group_id=? AND revision=?")) {
            sql.setString(1,profile); sql.setLong(2,now); sql.setString(3,self); sql.setString(4,group); sql.setLong(5,revision);
            return sql.executeUpdate() == 1;
        } catch (SQLException error) { throw failure(error); }
    }
    public synchronized void clearLearnedStyle(String self, String group) {
        requireId(self); requireId(group);
        try (var sql = database.prepareStatement("UPDATE group_style_profile SET profile='{}',revision=revision+1,updated_at=0 WHERE self_id=? AND group_id=?")) {
            sql.setString(1,self); sql.setString(2,group); sql.executeUpdate();
        } catch (SQLException error) { throw failure(error); }
    }

    private static void requireId(String id) {
        try { if (id == null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException error) { throw badRequest("机器人 QQ 号和群号必须为有效的正整数"); }
    }

    private static void inRange(int value, int min, int max, String name) {
        if (value < min || value > max) throw badRequest(name + "需在 " + min + "–" + max + " 之间");
    }

    /** Portable, data-only SQL; no credentials or executable user SQL is accepted via HTTP. */
    public synchronized String exportSql() {
        StringBuilder sql = new StringBuilder("-- Almond Eye Bot configuration. Back up uploads/images separately.\n");
        sql.append(schema).append("\nDELETE FROM tag_image_history;\nDELETE FROM bot_migration;\nDELETE FROM group_ai_usage;\nDELETE FROM group_policy;\nDELETE FROM reply_rule;\nDELETE FROM image_asset;\nDELETE FROM bot_settings;\n");
        try (Statement statement = database.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM bot_migration ORDER BY version")) {
            while (rows.next()) sql.append("INSERT INTO bot_migration VALUES(").append(quote(rows.getString(1))).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        sql.append("DELETE FROM bot_persona;\nINSERT INTO bot_persona(id,prompt) VALUES(1,")
                .append(quote(persona().prompt())).append(");\n");
        var boundary = contentBoundary();
        sql.append("DELETE FROM bot_content_boundary;\nINSERT INTO bot_content_boundary(id,prompt,revision) VALUES(1,")
                .append(quote(boundary.prompt())).append(',').append(boundary.revision()).append(");\n");
        sql.append("DELETE FROM twitter_subscription;\n");
        for(var item:twitterSubscriptions()) sql.append("INSERT INTO twitter_subscription VALUES(")
                .append(quote(item.self())).append(',').append(quote(item.target())).append(',').append(item.group()).append(',')
                .append(quote(item.alias())).append(',').append(quote(item.username())).append(',').append(quote(item.userId())).append(',')
                .append(quote(item.cursor())).append(',').append(quote(item.generation())).append(',').append(item.lastPoll()).append(");\n");
        Settings s = settings();
        sql.append("DELETE FROM group_memory;\n");
        try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM group_memory ORDER BY owner_key,ordinal")) {
            while (rows.next()) sql.append("INSERT INTO group_memory(owner_key,ordinal,user_id,sender_name,content,message_time,is_bot,is_summary) VALUES(")
                    .append(quote(rows.getString("owner_key"))).append(',').append(rows.getInt("ordinal")).append(',')
                    .append(quote(rows.getString("user_id"))).append(',').append(quote(rows.getString("sender_name"))).append(',')
                    .append(quote(rows.getString("content"))).append(',').append(rows.getLong("message_time")).append(',')
                    .append(rows.getBoolean("is_bot")).append(',').append(rows.getBoolean("is_summary")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        sql.append("DELETE FROM conversation_memory;\n");
        try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM conversation_memory ORDER BY owner_key,ordinal")) {
            while (rows.next()) sql.append("INSERT INTO conversation_memory(owner_key,ordinal,user_text,assistant_text,is_summary) VALUES(")
                    .append(quote(rows.getString("owner_key"))).append(',').append(rows.getInt("ordinal")).append(',')
                    .append(quote(rows.getString("user_text"))).append(',').append(quote(rows.getString("assistant_text"))).append(',')
                    .append(rows.getBoolean("is_summary")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        sql.append("INSERT INTO bot_settings VALUES(1,").append(s.aiEnabled()).append(',').append(s.wikiEnabled())
                .append(',').append(s.builtinImagesEnabled()).append(',').append(s.keywordRepliesEnabled())
                .append(',').append(s.groupRequireAt()).append(");\n");
        for (ImageAsset i : images()) sql.append("INSERT INTO image_asset VALUES(").append(i.id()).append(',')
                .append(quote(i.originalName())).append(',').append(quote(i.storedName())).append(',')
                .append(quote(i.contentType())).append(',').append(i.byteSize()).append(',').append(quote(i.createdAt())).append(");\n");
        for (ReplyRule r : rules()) sql.append("INSERT INTO reply_rule(id,keyword,match_type,reply_text,image_id,enabled,priority,action_type) VALUES(").append(r.id()).append(',')
                .append(quote(r.keyword())).append(',').append(quote(r.matchType())).append(',').append(quote(r.replyText()))
                .append(',').append(r.imageId() == null ? "NULL" : r.imageId()).append(',').append(r.enabled())
                .append(',').append(r.priority()).append(',').append(quote(r.action())).append(");\n");
        long imageNext = images().stream().mapToLong(ImageAsset::id).max().orElse(0) + 1;
        for (GroupPolicy g : groups()) sql.append("INSERT INTO group_policy(self_id,group_id,group_name,enabled,trigger_mode,keywords,cooldown_seconds,daily_limit,max_input_chars,max_output_tokens,context_turns,follow_up_seconds,auto_reply_rate) VALUES(").append(quote(g.selfId())).append(',')
                .append(quote(g.groupId())).append(',').append(quote(g.groupName())).append(',').append(g.enabled()).append(',')
                .append(quote(g.mode())).append(',').append(quote(g.keywords())).append(',').append(g.cooldownSeconds()).append(',')
                .append(g.dailyLimit()).append(',').append(g.maxInputChars()).append(',').append(g.maxOutputTokens()).append(',')
                .append(g.contextTurns()).append(',').append(g.followUpSeconds()).append(',').append(g.autoReplyRate()).append(");\n");
        try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM group_ai_usage")) {
            while (rows.next()) sql.append("INSERT INTO group_ai_usage VALUES(").append(quote(rows.getString("self_id"))).append(',')
                    .append(quote(rows.getString("group_id"))).append(",DATE ").append(quote(rows.getDate("usage_day").toString()))
                    .append(',').append(rows.getInt("call_count")).append(',').append(rows.getLong("last_attempt")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        long ruleNext = rules().stream().mapToLong(ReplyRule::id).max().orElse(0) + 1;
        sql.append("ALTER TABLE image_asset ALTER COLUMN id RESTART WITH ").append(imageNext).append(";\n")
                .append("ALTER TABLE reply_rule ALTER COLUMN id RESTART WITH ").append(ruleNext).append(";\n");
        sql.append("DELETE FROM bot_wows;\nINSERT INTO bot_wows(id,enabled) VALUES(1,").append(shipDataEnabled()).append(");\n");
        exportKnowledge(sql);
        sql.append("DELETE FROM group_persona;\n");
        try(var statement=database.createStatement();var rows=statement.executeQuery("SELECT * FROM group_persona ORDER BY self_id,group_id")) {
            while(rows.next()) sql.append("INSERT INTO group_persona VALUES(").append(quote(rows.getString("self_id"))).append(',')
                    .append(quote(rows.getString("group_id"))).append(',').append(quote(rows.getString("prompt"))).append(',').append(rows.getLong("revision")).append(");\n");
        } catch(SQLException error) {throw failure(error);}
        sql.append("DELETE FROM group_learning_settings;\nDELETE FROM learning_settings;\nINSERT INTO learning_settings VALUES(1,")
                .append(learningEnabled()).append(");\n");
        try (var statement=database.createStatement(); var rows=statement.executeQuery("SELECT * FROM group_learning_settings ORDER BY self_id,group_id")) {
            while(rows.next()) sql.append("INSERT INTO group_learning_settings VALUES(").append(quote(rows.getString("self_id"))).append(',')
                    .append(quote(rows.getString("group_id"))).append(',').append(rows.getBoolean("enabled")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        sql.append("DELETE FROM group_style_profile;\nDELETE FROM group_learning_budget;\n");
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT * FROM group_style_profile ORDER BY self_id,group_id")) {
            while (rows.next()) sql.append("INSERT INTO group_style_profile VALUES(").append(quote(rows.getString("self_id"))).append(',')
                    .append(quote(rows.getString("group_id"))).append(',').append(quote(rows.getString("profile"))).append(',')
                    .append(rows.getLong("revision")).append(',').append(rows.getLong("last_attempt")).append(',').append(rows.getLong("updated_at")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        try (var statement = database.createStatement(); var rows = statement.executeQuery("SELECT * FROM group_learning_budget")) {
            while (rows.next()) sql.append("INSERT INTO group_learning_budget VALUES(1,DATE ").append(quote(rows.getDate("usage_day").toString()))
                    .append(',').append(rows.getInt("attempts")).append(',').append(rows.getLong("last_attempt")).append(");\n");
        } catch (SQLException error) { throw failure(error); }
        return sql.toString();
    }


    public synchronized boolean shipDataEnabled() {
        try (var sql = database.createStatement(); var row = sql.executeQuery("SELECT enabled FROM bot_wows WHERE id=1")) { return row.next() && row.getBoolean(1); }
        catch (SQLException error) { throw failure(error); }
    }
    public synchronized void saveShipDataEnabled(boolean enabled) {
        try (var sql = database.prepareStatement("UPDATE bot_wows SET enabled=? WHERE id=1")) { sql.setBoolean(1, enabled); sql.executeUpdate(); }
        catch (SQLException error) { throw failure(error); }
    }
    public java.util.Map<String,Object> shipDataStatus() {
        var status = new java.util.HashMap<String,Object>(shipData.status()); status.put("enabled", shipDataEnabled()); return java.util.Map.copyOf(status);
    }
    public boolean matchesShips(String text) { return shipDataEnabled() && shipData.matches(text); }
    public Optional<LocalShipDataService.Query> queryShips(String text) { return shipData.query(text); }
    public void augmentShips(List<com.alibaba.fastjson2.JSONObject> messages, String text) { if (shipDataEnabled()) shipData.augment(messages, text); }

    public synchronized List<KnowledgeBase> knowledgeBases() {
        var result = new ArrayList<KnowledgeBase>();
        try (var sql = database.createStatement(); var rows = sql.executeQuery("SELECT * FROM knowledge_base ORDER BY id")) {
            while (rows.next()) result.add(new KnowledgeBase(rows.getLong("id"), rows.getString("name"), rows.getString("keywords"), rows.getBoolean("enabled")));
            return List.copyOf(result);
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized KnowledgeBase knowledgeBase(long id) {
        return knowledgeBases().stream().filter(item -> item.id() == id).findFirst().orElseThrow(() -> notFound("资料库不存在"));
    }

    public synchronized KnowledgeBase saveKnowledgeBase(Long id, KnowledgeInput input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().strip().length() > 100
                || input.keywords() == null || input.keywords().length() > 4000) throw badRequest("请输入名称（1–100 字符）和关键词（最多 4000 字符）");
        var keywords = splitKeywords(input.keywords()).stream().distinct().toList();
        if (keywords.isEmpty() || keywords.size() > 50 || keywords.stream().anyMatch(word -> word.length() > 100))
            throw badRequest("每个资料库需配置 1–50 个关键词，每个最多 100 字符，以逗号或换行分隔");
        if (id == null && knowledgeBases().size() >= 100) throw badRequest("最多创建 100 个资料库");
        if (id != null) knowledgeBase(id);
        String words = String.join("\n", keywords);
        try (var sql = database.prepareStatement(id == null ? "INSERT INTO knowledge_base(name,keywords,enabled) VALUES(?,?,?)"
                : "UPDATE knowledge_base SET name=?,keywords=?,enabled=? WHERE id=?", Statement.RETURN_GENERATED_KEYS)) {
            sql.setString(1, input.name().strip()); sql.setString(2, words); sql.setBoolean(3, input.enabled());
            if (id != null) sql.setLong(4, id);
            sql.executeUpdate();
            if (id == null) { try (var key = sql.getGeneratedKeys()) { key.next(); id = key.getLong(1); } }
            return knowledgeBase(id);
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized List<KnowledgeFile> knowledgeFiles(long library) {
        knowledgeBase(library);
        var result = new ArrayList<KnowledgeFile>();
        try (var sql = database.prepareStatement("SELECT id,library_id,original_name,stored_name,byte_size,created_at FROM knowledge_file WHERE library_id=? ORDER BY id")) {
            sql.setLong(1, library);
            try (var rows = sql.executeQuery()) { while (rows.next()) result.add(knowledgeFile(rows)); }
            return List.copyOf(result);
        } catch (SQLException error) { throw failure(error); }
    }

    public synchronized List<KnowledgeDocument> knowledgeDocuments(long library) {
        knowledgeBase(library);
        var result = new ArrayList<KnowledgeDocument>();
        try (var sql = database.prepareStatement("SELECT * FROM knowledge_file WHERE library_id=? ORDER BY id")) {
            sql.setLong(1, library);
            try (var rows = sql.executeQuery()) { while (rows.next()) result.add(new KnowledgeDocument(knowledgeFile(rows), rows.getString("content"))); }
            return List.copyOf(result);
        } catch (SQLException error) { throw failure(error); }
    }

    private KnowledgeFile knowledgeFile(ResultSet row) throws SQLException {
        return new KnowledgeFile(row.getLong("id"), row.getLong("library_id"), row.getString("original_name"),
                row.getString("stored_name"), row.getLong("byte_size"), row.getString("created_at"));
    }

    public synchronized KnowledgeFile uploadKnowledge(long library, MultipartFile file) throws IOException {
        var existing = knowledgeFiles(library);
        String name = file.getOriginalFilename();
        if (name == null || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".txt")) throw badRequest("仅支持 TXT 文件，文件名最多 200 字符");
        name = name.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1);
        if (file.isEmpty() || file.getSize() > 512 * 1024) throw badRequest("每个 TXT 文件需为 1 字节至 512KB");
        if (existing.size() >= 32 || existing.stream().mapToLong(KnowledgeFile::byteSize).sum() + file.getSize() > 4 * 1024 * 1024)
            throw badRequest("每个资料库最多 32 个文件、总计 4MB");
        byte[] bytes = file.getBytes();
        if (bytes.length > 512 * 1024) throw badRequest("文件过大");
        String content = decodeKnowledge(bytes);
        if (content.isBlank() || content.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t'))
            throw badRequest("文件为空或含二进制内容，请上传文本文件");
        String stored = UUID.randomUUID() + ".txt", created = Instant.now().toString();
        Path path = knowledgePath(stored);
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        try (var sql = database.prepareStatement("INSERT INTO knowledge_file(library_id,original_name,stored_name,byte_size,created_at,content) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, library); sql.setString(2, name); sql.setString(3, stored); sql.setLong(4, bytes.length);
            sql.setString(5, created); sql.setString(6, content); sql.executeUpdate();
            try (var keys = sql.getGeneratedKeys()) { keys.next(); return new KnowledgeFile(keys.getLong(1), library, name, stored, bytes.length, created); }
        } catch (SQLException error) { Files.deleteIfExists(path); throw failure(error); }
    }

    private String decodeKnowledge(byte[] bytes) {
        try {
            if (bytes.length >= 2 && ((bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) || (bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff)))
                return StandardCharsets.UTF_16.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", ""); }
            catch (java.nio.charset.CharacterCodingException ignored) {
                return java.nio.charset.Charset.forName("GB18030").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
        } catch (java.nio.charset.CharacterCodingException error) { throw badRequest("无法解码文件，请另存为 UTF-8 TXT 后导入"); }
    }

    private Path knowledgePath(String stored) throws IOException {
        if (!stored.matches("[a-f0-9-]{36}\\.txt") || Files.isSymbolicLink(knowledgeDirectory)
                || !knowledgeDirectory.toRealPath().equals(knowledgeDirectory.toAbsolutePath().normalize())) throw badRequest("无效的资料文件路径");
        Path path = knowledgeDirectory.resolve(stored);
        if (Files.isSymbolicLink(path)) throw badRequest("不支持符号链接文件");
        return path;
    }

    public synchronized void deleteKnowledgeFile(long library, long file) throws IOException {
        var asset = knowledgeFiles(library).stream().filter(item -> item.id() == file).findFirst().orElseThrow(() -> notFound("资料文件不存在"));
        Files.deleteIfExists(knowledgePath(asset.storedName()));
        delete("DELETE FROM knowledge_file WHERE id=?", file, "资料文件不存在");
    }

    public synchronized void deleteKnowledgeBase(long id) throws IOException {
        for (var file : knowledgeFiles(id)) Files.deleteIfExists(knowledgePath(file.storedName()));
        delete("DELETE FROM knowledge_base WHERE id=?", id, "资料库不存在");
    }

    private void exportKnowledge(StringBuilder sql) {
        sql.append("DELETE FROM knowledge_file;\nDELETE FROM knowledge_base;\n");
        long lastFile = 0;
        for (var base : knowledgeBases()) {
            sql.append("INSERT INTO knowledge_base(id,name,keywords,enabled) VALUES(").append(base.id()).append(',')
                .append(quote(base.name())).append(',').append(quote(base.keywords())).append(',').append(base.enabled()).append(");\n");
            for (var doc : knowledgeDocuments(base.id())) {
                var file = doc.file(); lastFile = Math.max(lastFile, file.id());
                sql.append("INSERT INTO knowledge_file(id,library_id,original_name,stored_name,byte_size,created_at,content) VALUES(").append(file.id()).append(',').append(base.id()).append(',')
                    .append(quote(file.originalName())).append(',').append(quote(file.storedName())).append(',').append(file.byteSize()).append(',')
                    .append(quote(file.createdAt())).append(',').append(quote(doc.content())).append(");\n");
            }
        }
        sql.append("ALTER TABLE knowledge_base ALTER COLUMN id RESTART WITH ").append(knowledgeBases().stream().mapToLong(KnowledgeBase::id).max().orElse(0) + 1).append(";\n");
        sql.append("ALTER TABLE knowledge_file ALTER COLUMN id RESTART WITH ").append(lastFile + 1).append(";\n");
    }

    private Path imagePath(ImageAsset image) {
        Path path = imageDirectory.resolve(image.storedName()).normalize();
        if (!path.startsWith(imageDirectory) || !image.storedName().matches("[a-f0-9-]{36}\\.(png|jpg|gif)"))
            throw badRequest("无效的图片文件名");
        return path;
    }

    private ReplyRule rule(ResultSet row) throws SQLException {
        return new ReplyRule(row.getLong("id"), row.getString("keyword"), row.getString("match_type"),
                row.getString("reply_text"), row.getObject("image_id", Long.class), row.getBoolean("enabled"), row.getInt("priority"), row.getString("action_type"));
    }

    private ImageAsset asset(ResultSet row) throws SQLException {
        return new ImageAsset(row.getLong("id"), row.getString("original_name"), row.getString("stored_name"),
                row.getString("content_type"), row.getLong("byte_size"), row.getString("created_at"));
    }

    private void delete(String sql, long id, String message) {
        try (PreparedStatement statement = database.prepareStatement(sql)) {
            statement.setLong(1, id);
            if (statement.executeUpdate() == 0) throw notFound(message);
        } catch (SQLException error) { throw failure(error); }
    }

    private static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
    private static IllegalStateException failure(SQLException error) { return new IllegalStateException("配置数据库操作失败", error); }

    @PreDestroy
    public synchronized void close() throws SQLException { summaries.close(); database.close(); }
}

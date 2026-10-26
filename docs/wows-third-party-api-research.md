# 战舰世界第三方战绩 API 核查

核查日期：2026-10-24（北京时间）。目标：为现有总体战绩、指定天数近期随机战查询寻找可替换的数据源。

后续进展：已进一步分析公开采集源码并独立提供总体/近期JSON接口；不依赖Yuyuko Token。原有数据源仍为WG累计数据＋自建历史快照，详见 [自建战绩API](wows-self-hosted-api.md)。下文保留最初第三方替换核查结论。

## 结论与当前状态

已确认 HikariBot 及其 Hikari-core SDK 存在总体和近期查询的真实调用实现，近期数据明确区分 `PVP`、单野、双排、三排及排位。其数据服务是 Yuyuko，不是 WoWS Numbers 的公开 API。

但“开源客户端存在”和“本项目已获得可用接口”是两个验收阶段：目前未取得服务方发放的有效 Token，未拿到真实玩家的成功响应，无法验证三服及指定天数的实际覆盖。本次没有切换生产代码，也没有更改配置或删除既有快照。当前仍使用 WG API 获取累计数据、本地快照计算近期差值。

## 可复核的一手来源

- [HikariBot](https://github.com/benx1n/HikariBot)：README 列出总体、近期及单船近期命令；部署要求 `API_TOKEN`，近期鉴权失败时要求检查或重新申请 Token。
- [Hikari-core](https://github.com/wows-yuyuko/Hikari-core)：Yuyuko API 客户端。此次静态读取 PyPI 的 `hikari-core 1.2.5` wheel，没有安装或执行包内代码。
- [SDK 发布元数据](https://pypi.org/pypi/hikari-core/1.2.5/json)：可复核发布包来源及哈希。
- [WowsApi](https://github.com/wows-yuyuko/WowsApi)：README 列出平台与近期服务的 Swagger 地址；当前 SDK 要求 JDK 21，不能直接作为本项目 Java 17 的依赖。

## Hikari-core 中的调用合同

以下为客户端源码事实，未作为线上成功合同验收：

| 用途 | 方法与路径 | 源码位置 |
| --- | --- | --- |
| 总体 | `GET /public/wows/account/user/info`，参数 `server, accountId` | `hikari_core/moudle/wws_info.py` |
| 近期 | `GET /api/wows/recent/day/info`，参数 `server, accountId, dateTime, day, shipId`；账号级查询 `shipId=0` | `hikari_core/moudle/wws_recent.py` |
| 默认主机 | `https://v3-api.wows.shinoaki.com` | `hikari_core/config.py` |
| 鉴权及客户端头 | `Authorization` 为配置 Token，另有 `Yuyuko-Client-Type` 和 `YUYUKO-INFO` | `hikari_core/HttpClient_Pool.py` |
| 随机战结果 | `data.battleTypeInfo.PVP`；模板从其 `battleInfo` 读取场次及均值，排位另列 | `hikari_core/Template/wws-info-recent.html` |

不能把 `PVP_SOLO` 当作全部随机战；不能用排位凑随机战数据。近期模板含胜率、场均伤害、经验、击沉及 PR 等，但实际字段类型、空值和时间范围仍需用合法成功响应验证。`dateTime` 与 `day` 的边界、区服枚举、服务时区、历史覆盖及无快照行为尚未完成确认。

## 本次联网验证

全部请求均不携带用户凭据；账号参数是用于检查端点行为的占位值，不代表真实玩家查询成功。

- 标准主机上的总体路径返回业务 `code=405`，说明账号不存在。只能证明路径有业务响应，不能证明总体数据成功。
- 标准主机及 `recent.wows.shinoaki.com:8890` 的近期路径均返回业务 `code=409`，提示请求头不符合标准。没有把 HTTP 200 当作战绩获取成功。
- 两个 README 所列 `/v3/index.html` 返回 Swagger 页面框架，但 `/v3/api-docs` 和页面引用的 `/v3/swagger-initializer.js` 返回业务 `code=5000`、No endpoint；本次未取得完整 OpenAPI 合同。不能据此推断服务整体停运。
- 未借用开源示例 Token，也未伪造客户端用户信息进行鉴权尝试。

## 其他候选

- [koishi-plugin-wws-kokomi](https://github.com/LolitaOT/koishi-plugin-wws-kokomi)：源码通过 `/bot/` 转发整条命令，返回 `msg` 或 `img`，并非已经验证的结构化战绩接口。旧默认地址为 HTTP，未将其接入本项目。
- [KokomiPJ_MainAPI](https://github.com/Hanserprpr/KokomiPJ_MainAPI)：存在近期数据模块；此次读取的 `app/apis/recent/recent_data.py` 中，按日期查询方法仍为占位实现。这个版本不足以证明某个部署实例支持本项目的指定天数需求。

## 替换前需要的材料与验收

需要服务方发放的有效 Yuyuko Token、当前接口合同（含客户端注册/信息头要求），以及至少一个可公开查询的测试玩家和区服。WG application_id 与 Yuyuko Token 不通用。Token 应保存在本地环境变量或私有配置中，不写入报告、Git 或聊天日志。

取得上述材料后才能实施并验证：昵称/数字 ID 解析；亚/欧/美区服映射；总体随机战；指定 N 天近期随机战及实际起止时间；零场次、隐藏账号、无历史、限流、鉴权与超时错误。报告继续使用单张图片，锐评随后独立输出。第三方缺少历史时明确提示，不悄悄改用更短区间，也不把总体冒充近期。切换应保留原快照库以便回退，默认源变化必须在报告和 README 中说明。

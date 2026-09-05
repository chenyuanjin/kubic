-- 考点 · MySQL 建表脚本
--
-- 这是仓库里第一个 .sql。它出现的触发条件写在
-- docs/technical/backend/B0-平台底座与横切契约.md §2.3:「第一次需要一台公网可达的服务器」。
-- KUBI-112 命中了这一条,所以 §2.4 的判据 `find . -name '*.sql' | wc -l` 从 0 变成 1。
--
-- ┄┄┄┄ 怎么改这个文件 ┄┄┄┄
-- 🔴 表结构 / 索引的任何改动【一律追加到文件末尾】,不许原地改上面的 CREATE TABLE。
--    原因:线上库已经按上面那份建过了,原地改的那一版谁也跑不到,而看文件的人会以为它跑到了。
--    追加的写法就是一段带日期与议题号的 ALTER:
--        -- 2026-XX-XX KUBI-NNN: 说明为什么
--        ALTER TABLE touch ADD COLUMN ...;
--
-- ┄┄┄┄ 🔴 红线:这里【没有、也不会有】能装下题干的列 ┄┄┄┄
-- 决策记录 §2.2 的能力边界:只答「有没有 / 几次 / 多久前」。
-- 所以全库自由文本只有三处,每一处都有硬上限,而且上限的真源在 Java 里不在这里:
--     touch.source_name        VARCHAR(60)  ← CreateRecordRequest.MAX_SOURCE_NAME_LENGTH
--     syllabus_node.name       VARCHAR(40)  ← FileSyllabusStore.MAX_NAME_LENGTH
--     syllabus_group.name      VARCHAR(40)  ← 同上
-- 其余全部是 id / 枚举 / 时间戳 / 整数 / 定点小数。
-- 不建的表(docs/technical/INDEX.md §5.2 已列):任何图片表、任何音频表、
-- 任何存题干 / 讲义 / 课程内容的表。原图只留用户本机,不进库、不上云。
-- 这条红线有一道机器闸看着:server/kaodian-app/src/test/java/.../redline/NoStemColumnTest.java
-- ——它拿 NoStemFieldTest 的同一份禁用词表扫这个文件的列名。
--
-- ┄┄┄┄ 为什么每张表都有 seq ┄┄┄┄
-- 文件实现的 findAll 顺序是「数组顺序」,而数组顺序就是写入顺序。
-- 换到 SQL 后「写入顺序」没有天然载体:同一毫秒落的两条记录,ORDER BY occurred_at 给不出稳定次序,
-- 而覆盖度的分子是「命中过的考点数」——顺序抖动会让同一份数据两次读出不同的时间线。
-- 所以每张按顺序读的表都带一个自增 seq 做 tie-break,业务主键另立唯一索引。

SET NAMES utf8mb4;

-- ═══════════════ 行为层 ═══════════════
-- 「几次 / 多久前」的唯一载体。一条记录 = 一次学习行为的发生,不是这次学习的内容。
CREATE TABLE IF NOT EXISTS touch (
    seq             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '写入序,只用于稳定排序',
    id              VARCHAR(64)     NOT NULL COMMENT '服务端签发 t-<UUID>',
    node_code       VARCHAR(64)     NOT NULL COMMENT '挂到哪个考点,取值必须是 syllabus_node.code',
    source_name     VARCHAR(60)     NOT NULL DEFAULT '' COMMENT '来源名,如「粉笔 · 资料分析系统班 L12」',
    kind            VARCHAR(16)     NOT NULL COMMENT 'VOICE/PHOTO/PASTE/DRILL/MANUAL',
    occurred_at     DATETIME(3)     NOT NULL COMMENT '发生时刻,「多久前」的唯一依据',
    drill_practiced INT             NULL COMMENT '做题数;非做题类为 NULL',
    drill_correct   INT             NULL COMMENT '做对数;与 practiced 同生同灭',
    client_token    VARCHAR(64)     NULL COMMENT '客户端去重键;没填就是 NULL,NULL 之间不互相判重',
    PRIMARY KEY (seq),
    UNIQUE KEY uk_touch_id (id),
    -- 🔴 幂等落在这条唯一索引上,不落在应用层的「先查再写」。
    -- 离线队列补传本来就是重发:同一批 50 条断线重连后再发一次,两次请求可以叠在一起,
    -- 各自查到「没有」再各自写一条 —— 用户看到记录变双份,而那正是覆盖度分子里的数。
    -- MySQL 的唯一索引允许多个 NULL,恰好等于 Touch 构造器把空白 client_token 归一成 null 的语义。
    UNIQUE KEY uk_touch_client_token (client_token),
    KEY idx_touch_node (node_code),
    KEY idx_touch_occurred (occurred_at, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行为层:一次学习行为的发生';

-- ═══════════════ 标签层 ═══════════════
-- 🔴 只存「后来发生的事」。每条记录自带的那个主标签【不占一行】,
-- 它由 RecordTag.effectiveTagsOf(touch, stored) 从 touch.node_code 现推。
-- 存下来的主标签行只贡献 confidence/origin/confirmed_at/discarded,node_code 永远以 touch 为准。
CREATE TABLE IF NOT EXISTS record_tag (
    seq          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '写入序,findByRecord 按它排',
    id           VARCHAR(64)     NOT NULL COMMENT 'tag-<UUID>,主标签是 primary-<recordId>',
    record_id    VARCHAR(64)     NOT NULL COMMENT '挂在哪条记录上;写入后不可变',
    node_code    VARCHAR(64)     NOT NULL COMMENT '挂到哪个考点;写入后不可变',
    confidence   DECIMAL(4,3)    NOT NULL COMMENT '0.000~1.000;manual 恒为 1.000',
    origin       VARCHAR(8)      NOT NULL COMMENT 'auto / manual;写入后不可变',
    confirmed_at DATETIME(3)     NULL COMMENT '用户确认时刻;NULL = 还没人确认',
    discarded    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '丢弃:仍可见,但不计覆盖度',
    PRIMARY KEY (seq),
    UNIQUE KEY uk_record_tag_id (id),
    KEY idx_record_tag_record (record_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签层:记录与考点之间的挂载';

-- ═══════════════ 断言层 ═══════════════
-- 「我会了」。一个考点最多一条,所以不发 id —— node_code 就是主键。
-- 没有「取消时刻」字段:取消就是删行。留一个 cancelled_at 等于把断言变成一条可查询的历史,
-- 而这个产品不记录用户的自我评价史。
CREATE TABLE IF NOT EXISTS user_assertion (
    seq         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '写入序,findAll 按它排',
    node_code   VARCHAR(64)     NOT NULL,
    asserted_at DATETIME(3)     NOT NULL COMMENT '按下按钮的时刻;重复断言不刷新',
    PRIMARY KEY (seq),
    UNIQUE KEY uk_user_assertion_node (node_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='断言层:用户自己声明掌握了';

-- ═══════════════ 骨架层 ═══════════════
-- 覆盖度的分母。三张表,因为树只有三层,而「第四层是结构上没这个位置」。
CREATE TABLE IF NOT EXISTS syllabus_subject (
    id              TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '单行表,恒为 1',
    code            VARCHAR(64)  NOT NULL,
    region          VARCHAR(40)  NOT NULL COMMENT '如「山东省考」',
    exam            VARCHAR(40)  NOT NULL COMMENT '如「行测」',
    module          VARCHAR(40)  NOT NULL COMMENT '如「资料分析」',
    recent5y_window VARCHAR(40)  NOT NULL COMMENT '如「2021-2025」',
    PRIMARY KEY (id),
    CONSTRAINT ck_syllabus_subject_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='骨架层:学科元信息(单行)';

CREATE TABLE IF NOT EXISTS syllabus_group (
    code       VARCHAR(64) NOT NULL COMMENT 'g-<8位hex>,服务端生成,不从名字派生',
    name       VARCHAR(40) NOT NULL COMMENT '题型名,原样存(只 strip)',
    -- 🔴 name_key 不是冗余。整棵树的名字唯一性口径是 SyllabusNames.nameKey():
    --    strip → NFKC → 剥不可见码点 → 折叠内部空白 → toLowerCase(ROOT)。
    --    MySQL 的 utf8mb4_0900_ai_ci 不做 NFKC、不剥零宽,唯一索引直接建在 name 上会漏判。
    --    存的是原样,比的是 name_key —— 与文件实现逐字同一条规则。
    name_key   VARCHAR(80) NOT NULL COMMENT 'SyllabusNames.nameKey(name),只用于查重',
    sort_order INT         NOT NULL COMMENT '树序;树序有产品含义,必须显式持久化',
    PRIMARY KEY (code),
    UNIQUE KEY uk_syllabus_group_name_key (name_key),
    KEY idx_syllabus_group_order (sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='骨架层:题型';

CREATE TABLE IF NOT EXISTS syllabus_node (
    code           VARCHAR(64) NOT NULL COMMENT 'n-<8位hex>',
    group_code     VARCHAR(64) NOT NULL,
    -- 40 字的上限本身就是一道闸,validName 还另外拒绝换行与控制字符:
    -- 一个带换行的考点名,几乎只可能是有人往里贴了一段题目。
    name           VARCHAR(40) NOT NULL COMMENT '考点名',
    name_key       VARCHAR(80) NOT NULL COMMENT '同 syllabus_group.name_key',
    recent5y_count INT         NOT NULL COMMENT '近五年频次,必填非负',
    archived       TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '归档:退出分母,记录仍在',
    sort_order     INT         NOT NULL COMMENT '组内树序;reorder 后已归档节点沉到末尾',
    PRIMARY KEY (code),
    -- 唯一性含已归档节点 —— 归档不是删除,名字仍被占着。
    UNIQUE KEY uk_syllabus_node_name_key (name_key),
    KEY idx_syllabus_node_group (group_code, sort_order),
    CONSTRAINT fk_syllabus_node_group FOREIGN KEY (group_code)
        REFERENCES syllabus_group (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='骨架层:考点(覆盖度的分母)';

-- ┄┄┄┄ 这一版【故意没建】的表 ┄┄┄┄
-- · 账号 / 令牌 / 注册流水 —— KUBI-112 仍留在文件存储。理由与迁移触发条件见 deploy/README.md §5。
-- · agent 的 run / message / tool_call / trace_event —— 它们的 text / arguments / result / detail
--   四个字段没有长度上限,建出来就是「能装下题干的列」。对话历史是整个仓库最容易长出内容的地方,
--   它继续落 ~/.kaodian/agent 的 ndjson,不进库。
-- · 验证码表 —— 进 Redis,靠 TTL 自然消亡。docs/technical/INDEX.md §5.2 原文就是「不建这张表」。
-- · record_event.extracted_text —— 文档 §5.2 那一列在代码里从来不存在,不要因为文档里有就建出来。

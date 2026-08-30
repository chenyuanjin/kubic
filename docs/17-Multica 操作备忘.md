# Multica 操作备忘

> **这份是查阅用的,不是决策层。** 交付流程的「为什么」在 `14`,agent 的职责在各自 instructions 里。
> 这里只放两样:**当前的 ID 表**,和**踩过的坑**。
>
> 每一条「坑」都真实卡过一次,不是可能出错的清单。写下来是因为它们全部属于
> **「命令返回成功了,但事情没有发生」** 这一类 —— 不看结果就发现不了。
>
> 实测于 2026-08-30,`multica v0.4.34`。

---

## 一、坐标

| 项 | 值 |
|---|---|
| 服务端 | `http://62.234.164.41:8080`(自建) |
| 界面 | `http://62.234.164.41:3000` |
| 工作空间 `kubicc` | `903c14c4-e5de-4e47-b3bc-3412818f4fa6` |
| CLI | `/usr/local/bin/multica` → app bundle 内的同一个二进制 |
| 本机守护进程 | `http://127.0.0.1:20226/health`(不走外网,可用来判断「是网络问题还是服务问题」) |

**每条命令都要带 `--workspace-id`**,否则落到默认工作空间。建议:

```bash
mc(){ multica --workspace-id 903c14c4-e5de-4e47-b3bc-3412818f4fa6 "$@"; }
```

---

## 二、ID 表

### Runtime(本机,另一台 MacBook-Pro-37 另有一套)

| 名称 | ID |
|---|---|
| Claude | `4c9548ca-c4c9-455d-a1ae-de062f3826fb` |
| Codex | `9ed607df-e25a-4081-aa84-968667c0e0b6` |
| Opencode | `886a2ee3-27be-4cdd-a6bb-fe4f32ba5796` |
| **Reasonix** | `5d4f778f-2dff-48fa-a865-d155f39e1872` |
| Cursor | `a330168e-8ab4-4c53-906d-1a29dcf79c48` |
| Hermes | `64452c92-f209-4262-a999-45a0d204007c` |
| Openclaw | `b2217384-501a-4dea-9f9b-1a8b9a53043d` |

### Agent(14 个)

| 小队 | 角色 | Runtime | ID |
|---|---|---|---|
| **设计组** | 产品经理 *(leader)* | Claude | `8450e644-b357-4412-9e8f-c2cac8ae4d02` |
| | 技术经理 | Claude | `fdbd9647-f271-4351-8b7e-0cac9cafe667` |
| | UI设计 | Claude | `239f8571-1034-458a-adb1-9dee8eae7a86` |
| | 项目经理 | Claude | `82f85c36-72a2-4591-8990-84f627e2ab57` |
| **开发组** | 后端与AI打标 *(leader)* | Claude | `2f08835f-1846-4e1e-8d37-0d2c8feacb60` |
| | 产品开发 | Claude | `1a5688e7-ace9-4562-aec5-dea50d37f42a` |
| | 跨端前端 | Claude | `decde66d-7fe8-4d63-a84f-083ade75fd8d` |
| **审核组** | 文档审核 *(leader)* | Codex | `ca2141d7-221f-406c-964b-0bd7e7a7b2f4` |
| | UI审核 | Opencode | `eca5be74-f89e-478e-90ca-b1a560075af4` |
| **测试组** | 功能测试 *(leader)* | Opencode | `85417614-876a-42b3-91dd-54571f9a3a37` |
| | 深度测试-推理 | Reasonix | `7bf92431-65c5-48de-a3e8-679fed5b5b39` |
| **轨道组** | 合规轨-执行 *(leader)* | Claude | `f6a1fcb9-c2d4-457b-b278-ea390851bb9b` |
| | 数据轨-开发 | Claude | `c90fb126-faf2-4f3b-8fe5-4df87768d073` |
| — | Mika(工作区自带) | Claude | `618c81a6-ebb6-4d37-a610-19c4de866643` |

### Squad(5 个)

| 名称 | ID |
|---|---|
| 设计组 | `99aa58db-ce77-4aa3-b272-9170f2ba0861` |
| 开发组 | `9981e5c2-9540-4103-b61c-7e93c3a1502c` |
| 审核组 | `71b969c0-4adf-46a6-a8e5-81891f6c8c7f` |
| 测试组 | `c3076b17-8ba6-41f5-ba82-cfc615c9b544` |
| 轨道组 | `d0bfd65f-4753-40f5-99d6-16f66ce80c5a` |

---

## 三、🔴 五个「返回成功但没生效」的坑

**这五条是同一个病:命令退出码 0,而事情没有发生。** 只看返回值发现不了,必须查结果。

### 1. 改状态【不会】启动 agent

```bash
mc issue status <id> in_progress     # ← 只改标签,没有 run
mc issue assign <id> --to 跨端前端    # ← 若它【已经】指派给该 agent,是空操作
mc issue rerun <id>                  # ✅ 这个才真正入队
```

**核实方法:`mc issue runs <id>`,空 = 从没跑过。** 状态是 `in_progress` 而 `runs` 为空,说明只是标签好看。

反过来也会:run 已经 `running` 而议题状态还停在 `todo` —— **状态标签滞后于实际执行**。判断在跑没跑,看 `runs` 不看 `status`。

### 2. `squad create --leader` 【不会】添加成员

`--leader` 只设队长。成员要单独加:

```bash
mc squad member add <squad-id> --member-id <agent-id> --role member
mc squad member list <squad-id>      # ← 建完必查
```

不查的话会得到「只有光杆队长的小队」,而且**看列表看不出来**——`squad list` 不返回成员。

另:agent 归档再恢复后,旧的 leader 身份还在,可能出现**一个小队两个 leader**。

### 3. `agent update --instructions` 是【整体覆盖】

不是追加。要先取旧的再拼:

```bash
cur=$(mc agent get <id> --output json | python3 -c "import sys,json;d=json.load(sys.stdin);a=d if 'instructions' in d else d.get('agent',d);print(a.get('instructions') or '')")
mc agent update <id> --instructions "${cur}${新增}"
```

### 4. `--runtime-id` 要 UUID,不收名字

传 `claude` / `codex` 会报 `invalid runtime_id`。用上面的 ID 表,或 `mc runtime list --output json`。

### 5. `cancel-task` 收的是 task-id,不是 issue-id

```bash
mc issue runs <issue-id>          # 先拿 task/run id
mc issue cancel-task <task-id>
```

---

## 四、命令语法(容易记错的)

```bash
# 评论 —— 是 comment add,不是 comment create;多行必须走 stdin
mc issue comment add <id> --content-stdin <<'EOF'
多行内容
EOF

# 状态 —— 位置参数,不是 --status
mc issue status <id> todo [--no-start]

# 建议题 —— stage 是关卡屏障(见下)
mc issue create --title X --parent <pid> --stage 2 --description "..." --output json

# 指派 —— --to 收 agent / squad / member 名字(模糊匹配)
mc issue assign <id> --to 开发组 [--no-start]
```

### `--stage N` = 关卡屏障

> Stage ordinal grouping this sub-issue into an ordered barrier group under its parent;
> **the parent assignee is woken only when every sub-issue in a stage finishes.**

一个 stage 全部完成才唤醒父议题的 assignee 去做判定 —— **这就是关卡,不用自己造。**

---

## 五、认证与沙箱

`multica auth status` 出现 `User:` 才算认证正常。

**三种失败要分开,处置完全不同:**

| 现象 | 实际是 | 怎么办 |
|---|---|---|
| `command not found` | PATH 缺 `/usr/local/bin` | 用绝对路径,别报成「认证失效」 |
| `invalid token` / 401 | 凭证真过期 | `multica login` |
| `task token was rejected` | **在 Multica 派的 task 里跑,而 task token 过期** | 只有派发方能续,**不要回退到个人凭证** |

第三种要看环境变量:有 `MULTICA_TASK_ID` / `MULTICA_TOKEN` / `MULTICA_TASK_CONFIG_ROOT` 就是在 task 沙箱里。
沙箱会把 CLI 配置根重定向到 task 专属目录,**`~/.multica/` 里的登录状态读不到,`--profile` 也切不出去**。
唯一出路是重新派一次任务。

---

## 六、跑在 Multica 里的 agent 要注意的

### 共享裸库会让基线核对误报

若 runtime 用共享裸库(`.repos/`),`git log origin/X..X` 会量到**别人 worktree 上的分支**。
判断「我这份是不是最新」要用:

```bash
git diff --stat HEAD origin/<分支>      # 空 = 一致
```

### 长耗时任务脱离进程后没人收尾

agent 把下载/构建脱离到后台,run 结束后**没有任何机制回来看结果**。
真实后果:一次 Android NDK 下载其实已经装完,而议题在 `blocked` 上挂了很久,
所有人都以为还差 NDK。

**对策:议题 `blocked` 时,顺带检查它 escalate 里写的前置条件现在是否已经满足。**

---

## 七、常用查询

```bash
mc agent list --output json | python3 -c "
import sys,json;d=json.load(sys.stdin)
rows=d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
[print(a['id'],a.get('name')) for a in rows]"

mc issue children <parent-id> --output json   # 子议题(含 stage)
mc issue runs <id>                            # 执行历史 —— 判断在没在跑
mc issue timeline <id>                        # 状态变更史,卡了多久
mc squad member list <squad-id>               # 小队成员

curl -s http://127.0.0.1:20226/health | python3 -m json.tool   # 守护进程 + 各工作空间 runtime
```

`scripts/health.sh` 已把其中几项做成定期检查。

---

## 八、一句话

**Multica 的命令几乎都会返回成功。** 这份备忘里一大半的内容,是在说同一件事:

**下达指令 ≠ 事情发生。改完必查结果。**

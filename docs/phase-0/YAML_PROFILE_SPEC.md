# YAML 基本资料镜像规范 v1

每个小镇对应 `plugins/TianjiTown/towns/<town-uuid>.yml`。MySQL 是权威源；YAML 只保存基本资料镜像。

允许人工修改的字段只有 `name`、`short-name`、`description`、`rules`、`public-settings`。`schema-version`、`town-id`、`revision`、`created-at`、`checksum` 为受保护元数据。任何额外字段都拒绝导入。

`checksum` 是以下规范字节流的 SHA-256 小写十六进制值：按固定字段顺序输出 `key=base64(UTF-8 value)\n`；换行统一为 LF，文本做 Unicode NFC；规则按索引输出；`public-settings` 按 key 排序；checksum 自身不参与计算。

写入使用同目录临时文件，再执行原子替换；文件系统不支持原子移动时门禁失败，不退化为非原子覆盖。人工修改不会在启动时自动导入或覆盖 MySQL，后续阶段必须经维护模式、备份、校验和显式确认完成导入。

当前白名单 `public-settings`：

- `listed`
- `accepting-invites`

长度限制：名称 1–24、简称 1–8、简介最多 500 字符、规则最多 50 条且单条最多 300 字符。

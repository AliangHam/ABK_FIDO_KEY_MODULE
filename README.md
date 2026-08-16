# ABK FIDO Key 模块

`abk_fido_key_module` 是一个 ABK 自定义外部内核模块,用于把 Android 手机侧内核扩展成一个**复合 USB FIDO2 安全密钥**。

当前版本:`0.2.0`

## 项目概览

该模块会:

- 安装一个树外(out-of-tree)内核驱动
- patch Android USB gadget 的 configfs 流程
- 在当前激活的 USB 配置上**自动追加一个 FIDO HID 接口**

它增加的内容:

- `common/drivers/abk_fido_key`(驱动源码)
- `common/include/linux/abk_fido_key.h`(公共内核头文件)
- `CONFIG_ABK_FIDO_KEY`
- `CONFIG_ABK_FIDO_KEY_CTAP2`
- `CONFIG_ABK_FIDO_KEY_GADGET_AUTO_ATTACH`
- `CONFIG_ABK_FIDO_KEY_PERSIST_METADATA`
- `CONFIG_ABK_FIDO_KEY_PERSIST_ADB_DATA`(兼容开关)
- `app/`:可选的 Android 配套应用,把内核存储 blob 镜像到 `/metadata` 上的 SQLite 数据库

## 仓库结构

- `setup.sh`:外部模块入口,供 ABK 构建钩子调用
- `module.conf`:模块元数据、版本和受支持的阶段
- `scripts/`:辅助 shell 和 Python patch 脚本,包括外部模块注入时使用的 KernelSU SELinux 策略打补丁脚本
- `files/drivers/abk_fido_key/`:内核驱动源码、Kconfig 和 Makefile
- `files/include/linux/abk_fido_key.h`:configfs 注入点使用的公共内核头文件
- `app/`、`build.gradle.kts`、`settings.gradle.kts`:最小的 Android 配套应用工程(用于基于元数据的 SQLite 镜像)

## 接入方式

### 前置条件

- 具有 `KERNEL_ROOT` 和 `common/` 内核树的 ABK 或 `new_test` 风格内核构建环境
- 构建环境中可用的 `python3`
- 与 `scripts/patch_configfs_for_abk_fido.py` 使用的 patch 锚点兼容的 `common/drivers/usb/gadget/configfs.c` 布局
- 如果希望配套应用把 SQLite 数据库镜像到 `/metadata`,设备上需要 root 权限

### 通用示例

把模块添加到 `new_test/.local-build/env.sh`:

```bash
export USE_CUSTOM_EXTERNAL_MODULES="true"
export CUSTOM_EXTERNAL_MODULES="/abs/path/to/abk_fido_key_module;after_patch|/abs/path/to/abk_fido_key_module;before_build"
```

### 仓库 URL 示例

如果你的 ABK 外部模块加载器支持直接拉取 Git URL,也可以直接使用公开仓库地址:

```bash
export USE_CUSTOM_EXTERNAL_MODULES="true"
export CUSTOM_EXTERNAL_MODULES="https://github.com/xingguangcuican6666/ABK_FIDO_KEY_MODULE.git;after_patch|https://github.com/xingguangcuican6666/ABK_FIDO_KEY_MODULE.git;before_build"
```

### 本地示例

```bash
export USE_CUSTOM_EXTERNAL_MODULES="true"
export CUSTOM_EXTERNAL_MODULES="/run/media/xingguangcuican/Project/kernelexp/new_test/abk_fido_key_module;after_patch|/run/media/xingguangcuican/Project/kernelexp/new_test/abk_fido_key_module;before_build"
```

然后重新构建:

```bash
./rebuild.sh --reseed
```

## 阶段行为

- `after_patch`:安装内核文件,并 patch `common/drivers/usb/gadget/configfs.c` 与 `common/drivers/kernelsu/selinux/rules.c`
- `before_build`:先执行 `after_patch` 的全部操作,再在 `DEFCONFIG` 中启用所需的 `CONFIG_ABK_FIDO_KEY*` 符号(包括元数据持久化开关)

该 patch 会把 `abk_fido_key_prepare_config()` 注入 gadget config bind 流程,在组装 USB gadget 时自动添加 `abk_fido` function。

## 运行期行为

- 在现有 Android 复合 gadget 之上额外增加一个 FIDO HID 接口
- 暴露调试 misc 节点 `/dev/hidgX`(X 通常为 0 到 3)
- 在 `/sys/kernel/abk_fido_key/` 下暴露只读状态节点:`enabled`、`bound`、`udc`、`hid_dev`、`credential_count`、`last_error`、`last_trace`、`store_generation`
- 暴露只写节点 `/sys/kernel/abk_fido_key/reload_store`,供用户态强制从元数据 blob 重新加载
- 暴露只写触发节点 `/sys/kernel/abk_fido_key/restore_metadata`,供用户态请求从持久化 store 文件严格恢复(无需通过 sysfs 传输 blob)
- 暴露 `/sys/kernel/abk_fido_key/store_blob` 作为仅供调试的当前 store 二进制视图;它不是 Android 用户态的主持久化/恢复路径
- 支持 CTAP HID 的 `INIT`、`PING`、`WINK`、`CBOR`、`CANCEL`
- 实现 CTAP2 的 `getInfo`、`makeCredential`、`getAssertion`、`clientPIN`(最小实现)、`reset`、`selection`
- 把内核侧 FIDO store blob 持久化到 `/metadata/abk_fido_store.bin`
- 构建注入期间,模块会 patch KernelSU SELinux 策略,使 `kernel` 域无需把 SELinux 切到 permissive 模式即可访问该元数据 blob
- 配套应用会把当前 blob 镜像到 SQLite 数据库,并保持 `/metadata/abk_fido.db` 中的 SQLite 镜像

## 验证方式

构建并启动成功后,检查以下内容:

- 驱动文件已复制到 `common/drivers/abk_fido_key`
- `CONFIG_ABK_FIDO_KEY=y` 及相关符号已启用
- `/sys/kernel/abk_fido_key/hid_dev` 报告 `hidgX` 设备名
- gadget 绑定后 `/sys/kernel/abk_fido_key/bound` 变为 `1`
- `/dev/hidgX` 存在,可进行包级调试
- 凭证或 PIN 变更后,`/metadata/abk_fido_store.bin` 存在
- 向 `/sys/kernel/abk_fido_key/restore_metadata` 写入 `1` 会递增 `store_generation` 并恢复到预期的 `credential_count`
- 恢复成功后 `/sys/kernel/abk_fido_key/last_error` 为空
- `/sys/kernel/abk_fido_key/last_trace` 报告元数据恢复路径
- 配套应用同步运行后,`/metadata/abk_fido.db` 存在

## GitHub 自动发布

- `.github/workflows/build-companion-app.yml` 在 GitHub Actions 上构建 debug 和 release APK
- 工作流使用 GitHub secrets 对 release APK 签名,然后使用 `gh release` 创建或更新目标 release,并上传 `abk-fido-companion-release.apk`
- 需要的仓库 secrets:
  - `ANDROID_SIGNING_KEYSTORE_BASE64`
  - `ANDROID_SIGNING_KEYSTORE_PASSWORD`
  - `ANDROID_SIGNING_KEY_ALIAS`
  - `ANDROID_SIGNING_KEY_PASSWORD`
- 推送到 `main` 或 `master` 会刷新滚动更新的 `latest` release;推送 `v*` 标签会把产物发布到对应的带标签 release

## 元数据

公开模块元数据位于 `module.conf`,并且应与发布后的仓库保持一致。配套应用的元数据也会在这里导出,以便 ABK 在提供内核模块的同时提供 FIDO SQLite 镜像 APK。

## 当前边界

- 这是面向注册/认证流程的第一版内核侧 CTAP2 实现
- 模块将自己标识为通用的 `Security Key`,不尝试模拟 YubiKey
- `clientPIN` 有意保持最小实现,不覆盖完整的进阶凭证管理扩展
- 内核 blob 是运行时状态的直接权威来源;配套 SQLite 数据库是镜像持久化层,通过 `/metadata/abk_fido_store.bin` 同步,而非内核内 SQLite
- 如果配套应用无法获得 root 权限,`/metadata/abk_fido.db` 将不会刷新,但内核 blob `/metadata/abk_fido_store.bin` 仍然是主持久化存储
- configfs patcher 依赖 `common/drivers/usb/gadget/configfs.c` 中的特定锚点;如果内核树出现分歧,必须更新 patch 步骤

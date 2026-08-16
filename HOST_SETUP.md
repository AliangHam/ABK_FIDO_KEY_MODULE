# 主机端接入指南(浏览器 / WebAuthn)

本文档说明如何在 Linux 主机上让**浏览器(Firefox/Chrome 等)通过 WebAuthn 使用 ABK FIDO Key**。

调试背景:在 https://www.token2.com/tools/fido2-demo 上浏览器无法发现设备、手机不弹出认证界面,根因是**缺少 FIDO udev 规则**与 **snap 沙箱未授权**,修复如下。

## 1. 添加 udev 规则

ABK FIDO Key 有两种 USB 形态,取决于手机当前模式:

| 模式 | idVendor | idProduct |
|---|---|---|
| USB 调试(adb) | `18d1`(Google) | `4e11` |
| MTP 普通模式 | `2717`(Xiaomi) | `ff40` |

新建 `/etc/udev/rules.d/70-fido.rules`:

```
# ABK FIDO Key (Xiaomi 15 via ABK kernel module)
# USB debugging mode: 18d1:4e11 ; normal MTP mode: 2717:ff40
# MODE/GROUP/uaccess: native (deb) apps
# TAG+="snap_firefox_firefox": snap Firefox
SUBSYSTEM=="hidraw", KERNEL=="hidraw*", ATTRS{idVendor}=="18d1", ATTRS{idProduct}=="4e11", MODE="0660", GROUP="plugdev", TAG+="uaccess", TAG+="snap_firefox_firefox"
SUBSYSTEM=="hidraw", KERNEL=="hidraw*", ATTRS{idVendor}=="2717", ATTRS{idProduct}=="ff40", MODE="0660", GROUP="plugdev", TAG+="uaccess", TAG+="snap_firefox_firefox"
```

重载规则并验证:

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger
sudo udevadm info /dev/hidraw8 | grep TAGS
```

预期输出应包含 `snap_firefox_firefox:uaccess:seat`。设备属主应变为 `root:plugdev`(把当前用户加入 `plugdev` 组:`sudo usermod -aG plugdev $USER`)。

## 2. snap 版 Firefox 的特殊处理

snap 沙箱通过 udev `TAG+="snap_firefox_firefox"` 授予硬件访问权。首次使用时还需确认接口已连接:

```bash
snap connections firefox | grep u2f
```

如果为空,执行:

```bash
sudo snap connect firefox:u2f-devices
```

之后**完全退出 Firefox 再重新打开**(进程必须重启才会重新读取设备授权),访问:

- https://www.token2.com/tools/fido2-demo
- https://webauthn.io
- https://github.com/settings/security(Passkeys)

## 3. 命令行验证(libfido2)

```bash
fido2-token -L          # 枚举设备
fido2-token -I /dev/hidraw8   # 读取设备信息
```

## 4. 排错速查

| 现象 | 原因 | 处理 |
|---|---|---|
| 浏览器提示"找不到安全密钥" | udev 规则缺失/snap 未授权 | 执行第 1、2 节步骤并重启浏览器 |
| 手机不弹认证界面 | 认证门控(auth gate)未触发 | 检查 `/sys/kernel/abk_fido_key/auth_gate_enabled` 应为 `1` |
| token2 显示 "Unrecognised authenticator" | AAGUID 不在站点厂商库 | **正常现象**,见下文 |
| token2 显示 "Attestation not anchored" | self-attestation,无厂商证书链 | **正常现象**,自制设备预期行为 |

> 说明:ABK FIDO Key 是自制内核模块实现,AAGUID `98ab1392-4dfb-da69-c679-2ebe51b2bb74` 不在任何商业厂商数据库中,attestation 采用 self-attestation。这两条提示均不影响注册/登录功能与密码学有效性。

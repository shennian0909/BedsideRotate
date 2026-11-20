# 功能

旨在解决躺着使用平板只能锁定方向，不能随心旋转屏幕的问题。
翻身保持画面朝向，主动转动平板切换横竖屏。
支持 Android 8.0 及以上。

## 使用

1. 打开应用，允许「修改系统设置」，再点「启动懒人翻身」。
2. 仰卧举起平板进入卧床模式。已经侧卧时，可点「绑定当前显示」。
3. 需要换方向时，像转动纸书一样转腕。想放下平板再接着看，可关闭「平放后自动回到桌面模式」。

普通应用默认跟随躺姿。开启前台应用识别后，可让游戏自动暂停接管，也可为指定应用选择「交还系统」或「固定横屏」。快捷设置磁贴可启停服务，主页可设置是否在最近任务中显示。

## 构建

使用 JDK 17、Android SDK Platform 35 和 Build-Tools 34.0.0。在工程根目录运行：

```sh
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 许可

项目采用 GNU 通用公共许可证第 3 版（GPL-3.0-only）。GitHub 图标来自 Octicons，使用 MIT 许可证。[项目许可证](LICENSE) · [图标许可证](app/src/main/assets/octicons-license.txt)

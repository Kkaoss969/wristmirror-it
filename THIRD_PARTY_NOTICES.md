# 第三方说明

蓝牙协议和表盘操作参考华为运动健康、腕连手表助手及 Gadgetbridge 的通信流程，并据此独立实现。本仓库不包含上述应用实现、业务 APK 或 SDK。

界面交互参考 [KernelSU](https://github.com/tiann/KernelSU) 管理器的 miuix 设计，收起顶栏和按压效果使用 Miuix 公开组件组合实现。

腕镜项目版权归 Escosis，采用 [Apache 2.0 许可证](<LICENSE>)。下列依赖与素材保留各自的版权、许可证及上游声明。

## 依赖与构建工具
- [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix) **0.9.3**：使用界面、偏好设置及其配套组件，采用 Apache 2.0 许可证。
- [AndroidX](https://developer.android.com/jetpack/androidx) 与 Jetpack Compose：用于界面、生命周期、导航事件及 Android 支持功能，采用 Apache 2.0 许可证，版权归 Android 开源项目及相应贡献者。
- [Kotlin](https://kotlinlang.org/) **2.4.0**、Compose 编译器插件及 [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) **1.10.2**：采用 Apache 2.0 许可证，版权归 JetBrains 及相应贡献者。
- Android Gradle 插件 **9.2.1** 与 [Gradle](https://gradle.org/) **9.4.1**：采用 Apache 2.0 许可证；标准 Gradle 包装器与启动脚本保留上游版权、许可证及 SPDX 声明。
- [JUnit](https://junit.org/junit4/license.html) **4.13.2**：仅用于单元测试，采用 Eclipse 公共许可证 1.0，不属于 Apache 2.0 许可的应用实现。

依赖版本由[版本目录](<gradle/libs.versions.toml>)及 Gradle 锁定文件记录；本说明不替代各依赖随附的完整许可与声明。[Apache 2.0 官方全文](https://www.apache.org/licenses/LICENSE-2.0.txt)同时附于本仓库许可证文件。

## 图标
- 部分矢量图标来自 Android 开源项目，采用 Apache 2.0 许可证；源文件的上游版权与许可头保持不变。由 AndroidX Material Icons 转换为 VectorDrawable 的图标在文件内注明转换。

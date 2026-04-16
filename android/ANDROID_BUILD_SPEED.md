# Gradle / Android 构建加速说明

本工程已启用或提供以下选项；并澄清一些**过时或错误**的写法。

## 已写入仓库的配置

| 项 | 位置 | 说明 |
|----|------|------|
| 构建缓存 | `gradle.properties` → `org.gradle.caching=true` | 重复构建明显变快 |
| 并行 | `org.gradle.parallel=true` | 多模块并行 |
| Daemon | `org.gradle.daemon=true` | 默认即建议开启 |
| JVM 内存 | `org.gradle.jvmargs` 提到 4G + ParallelGC | 按机器可改大/改小 |
| Kotlin 增量 | `kotlin.incremental=true` | |
| non-transitive R | `android.nonTransitiveRClass=true` | 减少 R 解析与编译量 |
| 关无用 buildFeatures | `app/build.gradle.kts` | `buildConfig/aidl/renderScript/shaders/viewBinding/dataBinding` |
| Lint | `checkReleaseBuilds=false`, `abortOnError=false` | 日常调试少挡路 |
| 仅 arm64（可选） | `pengpeng.onlyArm64=true` | 减少 native/打包开销 |

### 仅 arm64（真机调试推荐）

在 `gradle.properties` 中设置：

```properties
pengpeng.onlyArm64=true
```

会作用于 `:app`：只打 `arm64-v8a`。  
**x86/x86_64 模拟器** 需要改回 `false`，否则会装不上或缺 `.so`。

## 不建议照搬的“加速技巧”

1. **`-XX:MaxPermSize`**  
   Java 8 起已移除 PermGen，该参数无效或产生告警，**不要写**。

2. **`org.gradle.configureondemand=true`**  
   Gradle 8+ 已弃用，Gradle 9 请勿使用。

3. **`android { gradle.settings.offline=true }`**  
   语法不存在。离线应使用根目录 `gradle.properties` 里的 **`org.gradle.offline=true`**，或命令行 **`--offline`**。长期开着离线会导致**新依赖永远拉不下来**。

4. **`packagingOptions { optimizeResources false }`**  
   新版 AGP 已无此旧 API；调试阶段已通过 `minify/shrink` 关闭等方式减负。

5. **降级 AGP 到 7.4 / 8.1**  
   本工程使用 **compileSdk 36 + AGP 9.x**，降级会破坏工具链兼容性，**不建议**。

6. **`tasks.configureEach { enabled = !name.contains("copy") }`**  
   会误伤合法任务；本项目**未**采用。

## 可选：配置缓存（Configuration Cache）

在 `gradle.properties` 中可尝试（若插件报错再关掉）：

```properties
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=warn
```

## Android Studio

- **Settings → Build, Execution, Deployment → Compiler**：勾选 **Compile independent modules in parallel**（若仍有）。
- **Experimental → Only sync the active variant**：按需开启，减轻 Sync 时间。

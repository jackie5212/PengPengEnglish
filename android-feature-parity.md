# Android 已实现功能说明（与 Flutter 对齐用）

本文档基于仓库内 `Android/app/src/main/java/com/example/pengpengenglish/` 与相关资源整理，供在 `Flutter/` 中做功能对等实现时对照。

## 1. 应用结构概览

| 区域 | 说明 |
|------|------|
| 主界面 | `MainActivity`：Jetpack Compose，底部 Tab（首页 / 学习 / 工具 / 我的） |
| 子页面 | 多数「我的」相关页为 **独立 XML Activity**（`InfoPagesActivity.kt`、`StudyRecordActivity`、`VoiceDownloadActivity`） |
| 权限 | `INTERNET`；`usesCleartextTraffic=true`（明文 HTTP） |

## 2. 底部导航与页面状态

- **首页（TopPage.HOME）**
  - **词库选择**：从 SQLite `vocab_packs` 读取词包列表；`SharedPreferences` 键 `reading_progress` 保存 `last_pack_id`、`last_page_{packId}`、`last_in_word_page`。
  - **进入词库**：进入「单词分页列表」模式（`inWordPage=true`），每页 8 词；底栏 Tab 在此模式下隐藏。
  - **查单词**：对 `ecdict_entries` 做前缀模糊查询（`lower(word) LIKE lower(?)`，参数 `keyword%`）；分页每页 5 条；结果可点进详情。
  - **单词详情**：`AlertDialog` 展示音标、释义、例句与中译；可对 **单词** 与 **例句** 分别触发 TTS（最多 3 个快捷发音按钮）。
  - **翻页**：词库内底部按钮循环 `currentPage`（`下一页` 文案实为 `(page+1)%totalPages`）。
- **学习 / 工具**：占位文案「预留」。
- **我的（TopPage.MINE）**：Compose 内嵌菜单，跳转各 Activity 或子菜单。

系统返回键：`BackHandler` 处理详情 → 退出词库页 → 我的子菜单 → 回到首页。

## 3. 词典与数据库（`DictionaryDb.kt`）

### 3.1 主库 `ecdict.db`

- 首次从 **assets** 拷贝到应用私有目录，版本由 `SharedPreferences` `dictionary_db_meta` 中 `asset_db_version` 与常量 `ASSET_DB_VERSION`（当前 **3**）比对决定是否重新拷贝。
- 打开方式：`SQLiteDatabase.openDatabase(..., OPEN_READWRITE)`。
- **表与用途**：
  - `vocab_packs` / `vocab_pack_words`：词包与词序（`line_no`）。
  - `ecdict_entries`：词典条目；搜索、释义、音标均查此表。
- **启动时**：`ensureSchema` 可为 `vocab_pack_words` 增加 `example_sentence` 列（若不存在）。
- **内置词包**：`ensureBundledPacks` 从 assets `packs/primary_school_words.txt` 导入 `primary_school`（显示名「小学英语大纲词汇」）。

### 3.2 例句库 `example_sentence.db`

- 从 assets 拷贝，`example_db_version`（当前 **1**）。
- 表 `example_sentences`：`word`、`sentence_en`、`sentence_cn`、`heat` 等；按 `heat` 降序取一条例句（非小学包）。

### 3.3 小学专用例句 `primary_examples.db`

- 不在 assets 中预置完整文件；首次在本地 **生成**。
- 数据源：`packs/primary_school_words.txt`、`packs/primary_school_examples.txt`（英/中文行配对规则与 Kotlin 一致）。
- 表 `primary_examples`：`word`、`sentence_en`、`sentence_cn`、`priority`。

### 3.4 对外 API（需在 Flutter 侧对等）

- `getPacks()`、`countWords(packId)`、`getWordsPage(packId, limit, offset)`  
- `countSearchWords` / `searchWords`（limit/offset）  
- `lookupMeaning(word)`、`lookupPhonetic(word)`  
- 数据类：`WordEntry(word, example, exampleCn)`、`VocabPack(id, name, words)`（Kotlin 中 `words` 常为空列表，仅元数据）。

## 4. 学习记录（`StudyHistoryRepository.kt`）

- 存储：`SharedPreferences` 名 `study_history`，键 `records_json`，JSON 数组。
- 每条：`id`、`packId`、`packName`、`pageIndex`、`totalPages`、`savedAtMs`。
- **同 packId 只保留最新一条**（新记录插入前删除同 pack 旧项）；最多 **5** 条（`MAX_RECORDS`）。
- 在首页处于词库浏览且 `inWordPage` 时，随 `LaunchedEffect` 调用 `recordSession`。
- 退出词库返回首页时也会 `recordSession`。
- `ensureDefaultSamplesIfEmpty()`：无记录时写入 5 条示例（仅用于界面效果）。

## 5. Piper 离线 TTS（`PiperOnnxTtsManager.kt`）

- 引擎：**sherpa-onnx** `OfflineTts`（VITS 配置：`OfflineTtsVitsModelConfig`），依赖 **`Android/app/libs/sherpa-onnx-1.12.33.aar`**。
- **内置模型**：`models/amy/` → `en_US-amy-medium.onnx`、`tokens.txt`、`espeak-ng-data/`，运行时解压到 `noBackupFilesDir/piper/{modelId}/`。
- **已下载模型**：扫描 `noBackupFilesDir/piper/` 下子目录；需 `.onnx`、`tokens.txt`、`espeak-ng-data`（非空）；`voice_name.txt` 可选，用于列表展示名。
- **兼容性**：`isRuntimeCompatible` 要求模型 id 含 `-int8` 或 `-fp16`，否则标为不支持（防部分机型崩溃）。
- **能力**：`init`、`speak`（生成 wav 到 cache + `MediaPlayer`）、`release`、`preloadModels`、LRU 缓存最多 2 个引擎。

Flutter 侧：Android 建议通过 **MethodChannel** 调用同一套原生逻辑（见下文「Flutter Android 接入要点」）。

## 6. 语音下载（`VoiceDownloadActivity.kt`）

- 模型列表 URL：`http://47.97.36.224/tts/model.txt`  
- 下载基址：`http://47.97.36.224/tts/{fileName}`  
- 解析规则：`#` 行为注释；`[显示名]` 下一行 `xxx.tar.bz2` 为一条模型；无方括号则用文件名去后缀作为显示名。
- 下载到 `cacheDir/voice-downloads/`，解压到 `noBackupFilesDir/piper/{archiveBaseName}/`，仅抽取 `.onnx`、`tokens.txt`、`espeak-ng-data/**`。
- 解压后写入 `voice_name.txt`；校验 onnx/tokens/espeak。
- 已安装则显示「删除」：清空 `piper/{id}` 与缓存包。

## 7. 版本检查（`VersionInfoActivity`）

- 远程：`http://47.97.36.224/tts/version.txt`，读取首行形如 `ReleaseVersion: x.y.z`。
- 与 `PackageManager` 本地 `versionName` 比较；较新则弹窗跳转应用市场（按安装来源尝试 QQ 应用宝 / vivo 商店等，失败则 H5）。

## 8. 其它 Activity（多为静态 XML）

- `ProfileActivity`、`FavoritesActivity`、`GeneralSettingsActivity`、`PlaybackSettingsActivity`、`PrivacySettingsActivity`：当前为 **占位布局**（标题 + 返回）。
- `UserAgreementActivity`、`PrivacyPolicyActivity`、`AcknowledgementsActivity`、`DeveloperInfoActivity`：长文占位。
- `PhoneInfoActivity`：`Build` 各字段拼接展示。

## 9. 未接入主流程的代码

- `MainActivity.kt` 内 `WordStateRepository`（学习/回收站 prefs）已定义，**当前主界面未引用**；Flutter 可暂不实现，或作为后续扩展。

---

## 10. Flutter 实现清单（对照表）

| 功能 | Flutter 建议 |
|------|----------------|
| 词典主库 / 例句库 / 小学例句生成 | `sqflite` + 首次拷贝 assets + 与 Kotlin 相同的 SQL/业务逻辑 |
| 阅读进度 / 学习记录 | `shared_preferences`，键名与 Android 保持一致便于日后数据迁移（可选） |
| 查词、分页、详情弹窗 | `Material` 页面 + `Dialog` / `showModalBottomSheet` |
| Piper TTS（Android） | `MethodChannel` + 拷贝 `PiperOnnxTtsManager` 与 `sherpa-onnx` aar 至 Flutter 子工程 `android/app` |
| 语音下载与解压 | `http` + `archive`（BZip2 + Tar）或平台代码；目录与 Android 一致便于共用已下载模型 |
| 版本检查 | `http` 拉取 `version.txt` + `package_info_plus` |
| 学习/工具占位 | 占位页 |
| 我的子页 | 对应 `Navigator` 页面；静态文案与 Android XML 对齐即可 |

## 11. 资源文件（从 Android 复制到 Flutter）

将下列内容复制到 `Flutter/assets/`（与 `pubspec.yaml` 中声明一致）。可在仓库根目录用 PowerShell 示例：

```powershell
New-Item -ItemType Directory -Force -Path Flutter\assets\packs, Flutter\assets\models\amy | Out-Null
Copy-Item Android\app\src\main\assets\ecdict.db Flutter\assets\
Copy-Item Android\app\src\main\assets\example_sentence.db Flutter\assets\
Copy-Item Android\app\src\main\assets\packs\* Flutter\assets\packs\ -Recurse
Copy-Item Android\app\src\main\assets\models\amy\* Flutter\assets\models\amy\ -Recurse
```

涉及文件：

- `ecdict.db`、`example_sentence.db`
- `packs/primary_school_words.txt`、`packs/primary_school_examples.txt`
- `models/amy/` 下 Piper 所需全部文件（与 Android assets 一致）

## 12. 初始化 Flutter 原生工程（若目录仅有 Dart）

本仓库已在 `PengPengEnglish/flutter_sdk` 下通过 Gitee 镜像克隆 **Flutter stable**（因直连 GitHub 易超时），并已把 `flutter_sdk\\bin` 加入当前 Windows 用户的 **PATH**。新开终端后可直接执行 `flutter`。若在国内构建，建议在会话中设置：

`PUB_HOSTED_URL=https://pub.flutter-io.cn`、`FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn`。

在已配置 Flutter SDK 的机器上：

```bash
cd Flutter
flutter create . --project-name pengpeng_english --org com.example
```

将 `org` / `applicationId` 设为 **`com.example.pengpeng_english`**（与 `Flutter/integration_android/MainActivity.kt` 包名一致），或自行全局替换包名。

然后将 **Piper** 所需内容合并到生成的 `android/app`：

1. 复制 `Android/app/libs/sherpa-onnx-1.12.33.aar` → `Flutter/android/app/libs/`。
2. 在 `android/app/build.gradle.kts` 中增加 `implementation(files("libs/sherpa-onnx-1.12.33.aar"))`（commons-compress 仅当解压由 Kotlin 实现时需要；当前 Flutter 端下载解压为 Dart）。
3. 用本仓库 **`Flutter/integration_android/PiperOnnxTtsManager.kt`**、**`MainActivity.kt`** 覆盖生成工程中的对应文件（或合并 `configureFlutterEngine` 与 Piper 类）。
4. `AndroidManifest.xml` 增加 `INTERNET`，若仍使用明文 HTTP，需 `android:usesCleartextTraffic="true"`（与现有 Android 应用一致）。

Dart 侧在启动时会通过 **`PiperAssetBootstrap`** 把 `assets/models/amy/**` 写入 `noBackupFilesDir/piper/amy_int8/`，因此集成版 `PiperOnnxTtsManager` 将内置 Amy 的 `assetDir` 设为 `null`，不再从 Android `assets` 拷贝。

本仓库 `Flutter/lib/services/piper_tts_platform.dart`、`native_paths.dart` 已与上述 channel 名与方法名约定一致。

## 13. 远程 URL 汇总

| 用途 | URL |
|------|-----|
| 模型列表 | `http://47.97.36.224/tts/model.txt` |
| 模型文件 | `http://47.97.36.224/tts/{fileName}` |
| 版本文件 | `http://47.97.36.224/tts/version.txt` |

---

*文档随 Android 代码变更时请同步更新 `ASSET_DB_VERSION`、依赖版本与 URL。*

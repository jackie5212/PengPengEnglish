package com.example.pengpengenglish

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.pengpengenglish.ui.theme.PengPengEnglishTheme
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PengPengEnglishTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VocabApp(
                        context = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun VocabApp(context: Context, modifier: Modifier = Modifier) {
    val appStartMs = remember { SystemClock.elapsedRealtime() }
    val progressPrefs = remember { context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE) }
    val studyHistory = remember { StudyHistoryRepository(context) }
    val dictionaryDb = remember { DictionaryDb(context) }
    val packs = remember { dictionaryDb.getPacks() }
    if (packs.isEmpty()) {
        Column(modifier = modifier.padding(16.dp)) {
            Text("词库数据库为空或读取失败")
        }
        return
    }
    val piperTts = remember { PiperOnnxTtsManager(context) }
    var piperModels by remember { mutableStateOf(piperTts.availableModels()) }
    var selectedPiperModel by remember { mutableStateOf(PiperOnnxTtsManager.ModelOption.AMY_INT8) }
    val initialPack = remember(packs) {
        val savedPackId = progressPrefs.getString("last_pack_id", null)
        packs.firstOrNull { it.id == savedPackId } ?: packs.first()
    }
    var selectedPack by remember { mutableStateOf(initialPack) }
    var packMenuExpanded by remember { mutableStateOf(false) }
    var inWordPage by remember { mutableStateOf(progressPrefs.getBoolean("last_in_word_page", false)) }
    var currentPage by remember {
        mutableStateOf(
            progressPrefs.getInt("last_page_${initialPack.id}", 0).coerceAtLeast(0)
        )
    }
    var statusText by remember { mutableStateOf("Piper 初始化中…") }
    var piperReady by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<WordEntry?>(null) }
    var detailMeaning by remember { mutableStateOf("") }
    var detailPhonetic by remember { mutableStateOf("") }
    var topPage by remember { mutableStateOf(TopPage.HOME) }
    var myPage by remember { mutableStateOf(MyPageRoot.ROOT) }
    var searchKeyword by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<WordEntry>>(emptyList()) }
    var searchHint by remember { mutableStateOf("输入英文单词后点击搜索") }
    var searchTotal by remember { mutableStateOf(0) }
    var searchPage by remember { mutableStateOf(0) }
    val searchPageSize = 5
    val quickVoiceModels = remember(piperModels) { piperModels.filter { it.isSupported }.take(3) }
    val pageSize = 8
    val totalWords = remember(selectedPack.id) { dictionaryDb.countWords(selectedPack.id) }
    val totalPages = ((totalWords + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safeCurrentPage = currentPage.coerceIn(0, totalPages - 1)
    if (safeCurrentPage != currentPage) {
        currentPage = safeCurrentPage
    }
    val pageStart = currentPage * pageSize
    val pageWords = remember(selectedPack.id, currentPage) {
        dictionaryDb.getWordsPage(selectedPack.id, pageSize, pageStart)
    }

    LaunchedEffect(Unit) {
        Log.d(PERF_TAG, "VocabApp first composition ready, cost=${SystemClock.elapsedRealtime() - appStartMs}ms")
        // Let home UI appear first, then initialize Piper in background.
        delay(300)
        val t0 = SystemClock.elapsedRealtime()
        val host = context as? ComponentActivity
        thread(start = true, name = "piper-init") {
            piperTts.init(
                option = selectedPiperModel,
                onReady = {
                    host?.runOnUiThread {
                        piperReady = true
                        statusText = "Piper 已就绪（${selectedPiperModel.label}）"
                        Log.d(PERF_TAG, "initial piper init ready, total=${SystemClock.elapsedRealtime() - t0}ms")
                    }
                },
                onError = { msg ->
                    host?.runOnUiThread {
                        statusText = "Piper 初始化失败：$msg"
                        Log.d(PERF_TAG, "initial piper init failed, total=${SystemClock.elapsedRealtime() - t0}ms, msg=$msg")
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { piperTts.release() }
    }

    LaunchedEffect(selectedPack.id) {
        val savedPage = progressPrefs.getInt("last_page_${selectedPack.id}", 0).coerceAtLeast(0)
        currentPage = savedPage
    }

    LaunchedEffect(selectedPack.id, currentPage, inWordPage, totalPages) {
        progressPrefs.edit()
            .putString("last_pack_id", selectedPack.id)
            .putInt("last_page_${selectedPack.id}", currentPage.coerceAtLeast(0))
            .putBoolean("last_in_word_page", inWordPage)
            .apply()
        if (inWordPage) {
            val p = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
            studyHistory.recordSession(selectedPack.id, selectedPack.name, p, totalPages)
        }
    }

    fun shortModelLabel(model: PiperOnnxTtsManager.ModelOption): String {
        return model.label.substringBefore(" (").substringBefore(" ").ifBlank { model.label }
    }

    fun voiceInitial(model: PiperOnnxTtsManager.ModelOption): String {
        val c = shortModelLabel(model).trim().firstOrNull()?.uppercaseChar() ?: '?'
        return c.toString()
    }

    fun speakWithModel(model: PiperOnnxTtsManager.ModelOption, text: String) {
        if (!model.isSupported) {
            statusText = "模型不兼容：${model.label}"
            return
        }
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        val doSpeak: () -> Unit = {
            statusText = "Piper 播放中（${model.label}）"
            piperTts.speak(trimmed) { msg ->
                statusText = "Piper 播放失败：$msg"
            }
        }
        if (selectedPiperModel.id == model.id && piperReady) {
            doSpeak()
            return
        }
        selectedPiperModel = model
        piperReady = false
        statusText = "Piper 初始化中（${model.label}）"
        piperTts.init(
            option = model,
            onReady = {
                piperReady = true
                statusText = "Piper 已就绪（${model.label}）"
                doSpeak()
            },
            onError = { msg ->
                statusText = "Piper 初始化失败：$msg"
            }
        )
    }

    val handleSystemBack = detailEntry != null ||
        inWordPage ||
        (topPage == TopPage.MINE && myPage != MyPageRoot.ROOT) ||
        topPage != TopPage.HOME

    BackHandler(enabled = handleSystemBack) {
        when {
            detailEntry != null -> {
                detailEntry = null
            }
            inWordPage -> {
                val p = currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                progressPrefs.edit()
                    .putString("last_pack_id", selectedPack.id)
                    .putInt("last_page_${selectedPack.id}", p)
                    .putBoolean("last_in_word_page", false)
                    .apply()
                studyHistory.recordSession(selectedPack.id, selectedPack.name, p, totalPages)
                inWordPage = false
            }
            topPage == TopPage.MINE && myPage != MyPageRoot.ROOT -> {
                myPage = MyPageRoot.ROOT
            }
            topPage != TopPage.HOME -> {
                topPage = TopPage.HOME
                myPage = MyPageRoot.ROOT
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (topPage) {
                TopPage.HOME -> {
                    if (!inWordPage) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("第一屏：请选择词库")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "当前词库：${selectedPack.name}",
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { packMenuExpanded = true }
                                            .padding(10.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val models = piperTts.availableModels()
                                            piperModels = models
                                            currentPage = progressPrefs
                                                .getInt("last_page_${selectedPack.id}", 0)
                                                .coerceAtLeast(0)
                                            inWordPage = true
                                            val preloadTargets = models.filter { it.isSupported }.take(2)
                                            thread(start = true, name = "piper-preload") {
                                                piperTts.preloadModels(preloadTargets)
                                            }
                                        }
                                    ) { Text("进入词库") }
                                }
                                DropdownMenu(expanded = packMenuExpanded, onDismissRequest = { packMenuExpanded = false }) {
                                    packs.forEach { pack ->
                                        DropdownMenuItem(
                                            text = { Text(pack.name) },
                                            onClick = {
                                                selectedPack = pack
                                                packMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .padding(10.dp)
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("查单词")
                                        TextField(
                                            value = searchKeyword,
                                            onValueChange = { searchKeyword = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            label = { Text("输入英文") }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val kw = searchKeyword.trim()
                                                    if (kw.isBlank()) {
                                                        searchResults = emptyList()
                                                        searchTotal = 0
                                                        searchPage = 0
                                                        searchHint = "请输入要查询的英文单词"
                                                    } else {
                                                        searchPage = 0
                                                        searchTotal = dictionaryDb.countSearchWords(kw)
                                                        searchResults = dictionaryDb.searchWords(
                                                            keyword = kw,
                                                            limit = searchPageSize,
                                                            offset = 0
                                                        )
                                                        searchHint = if (searchTotal == 0) {
                                                            "未找到匹配单词"
                                                        } else {
                                                            "共找到 $searchTotal 条"
                                                        }
                                                    }
                                                }
                                            ) { Text("搜索") }
                                            Button(
                                                onClick = {
                                                    searchKeyword = ""
                                                    searchResults = emptyList()
                                                    searchTotal = 0
                                                    searchPage = 0
                                                    searchHint = "输入英文单词后点击搜索"
                                                }
                                            ) { Text("清空") }
                                        }
                                        Text(searchHint)
                                        if (searchResults.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                searchResults.forEach { entry ->
                                                    Card(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                detailEntry = entry
                                                                detailMeaning = dictionaryDb.lookupMeaning(entry.word)
                                                                detailPhonetic = dictionaryDb.lookupPhonetic(entry.word)
                                                            }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(
                                                                modifier = Modifier.weight(1f),
                                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                                            ) {
                                                                Text(entry.word)
                                                                if (entry.example.isNotBlank()) Text(entry.example)
                                                            }
                                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                quickVoiceModels.forEach { model ->
                                                                    Button(onClick = {
                                                                        speakWithModel(model, entry.word)
                                                                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                                                        Text("▶${voiceInitial(model)}")
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                val searchTotalPages = ((searchTotal + searchPageSize - 1) / searchPageSize).coerceAtLeast(1)
                                                if (searchTotalPages > 1) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Button(
                                                            onClick = {
                                                                val nextPage = (searchPage - 1).coerceAtLeast(0)
                                                                searchPage = nextPage
                                                                searchResults = dictionaryDb.searchWords(
                                                                    keyword = searchKeyword.trim(),
                                                                    limit = searchPageSize,
                                                                    offset = nextPage * searchPageSize
                                                                )
                                                            },
                                                            enabled = searchPage > 0,
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("上一页") }
                                                        Text("${searchPage + 1}/$searchTotalPages")
                                                        Button(
                                                            onClick = {
                                                                val nextPage = (searchPage + 1).coerceAtMost(searchTotalPages - 1)
                                                                searchPage = nextPage
                                                                searchResults = dictionaryDb.searchWords(
                                                                    keyword = searchKeyword.trim(),
                                                                    limit = searchPageSize,
                                                                    offset = nextPage * searchPageSize
                                                                )
                                                            },
                                                            enabled = searchPage < searchTotalPages - 1,
                                                            modifier = Modifier.weight(1f)
                                                        ) { Text("下一页") }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                pageWords.forEach { entry ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                detailEntry = entry
                                                detailMeaning = dictionaryDb.lookupMeaning(entry.word)
                                                detailPhonetic = dictionaryDb.lookupPhonetic(entry.word)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(text = entry.word)
                                                if (entry.example.isNotBlank()) {
                                                    Text(entry.example)
                                                }
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                quickVoiceModels.forEach { model ->
                                                    Button(onClick = {
                                                        speakWithModel(model, entry.word)
                                                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                                        Text("▶${voiceInitial(model)}")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = { currentPage = (currentPage + 1) % totalPages },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("${currentPage + 1}/$totalPages") }
                        }
                    }
                }

                TopPage.STUDY -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("学习")
                            Text("学习功能页（预留）")
                        }
                    }
                }

                TopPage.TOOLS -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("工具")
                            Text("工具功能页（预留）")
                        }
                    }
                }

                TopPage.MINE -> {
                    MinePage(
                        current = myPage,
                        onNavigate = { myPage = it }
                    )
                }
            }
        }

        if (!(topPage == TopPage.HOME && inWordPage)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopPage.entries.forEach { page ->
                    TextButton(
                        onClick = {
                            topPage = page
                            if (page != TopPage.MINE) myPage = MyPageRoot.ROOT
                        }
                    ) {
                        Text(page.label)
                    }
                }
            }
        }
    }

    detailEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { detailEntry = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.word)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        quickVoiceModels.forEach { model ->
                            Button(onClick = {
                                speakWithModel(model, entry.word)
                            }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("▶${voiceInitial(model)}")
                            }
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detailPhonetic.isNotBlank()) {
                        Text("/$detailPhonetic/")
                    }
                    Text(if (detailMeaning.isNotBlank()) detailMeaning else "未找到释义")
                    if (entry.example.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.example)
                                if (entry.exampleCn.isNotBlank()) {
                                    Text(entry.exampleCn)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                quickVoiceModels.forEach { model ->
                                    Button(onClick = {
                                        speakWithModel(model, entry.example)
                                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text("▶${voiceInitial(model)}")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailEntry = null }) { Text("关闭") }
            }
        )
    }
}

private const val PERF_TAG = "PPStartPerf"

data class VocabPack(
    val id: String,
    val name: String,
    val words: List<String>
)

class WordStateRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("word_state", Context.MODE_PRIVATE)

    private fun keyLearned(packId: String, word: String) = "learned::$packId::${word.lowercase()}"
    private fun keyRecycle(packId: String) = "recycle::$packId"

    fun isLearned(packId: String, word: String): Boolean = prefs.getBoolean(keyLearned(packId, word), false)

    fun setLearned(packId: String, word: String, learned: Boolean) {
        prefs.edit().putBoolean(keyLearned(packId, word), learned).apply()
    }

    fun getRecycle(packId: String): MutableList<String> {
        val csv = prefs.getString(keyRecycle(packId), "") ?: ""
        if (csv.isBlank()) return mutableListOf()
        return csv.split("||").filter { it.isNotBlank() }.toMutableList()
    }

    fun isInRecycle(packId: String, word: String): Boolean = getRecycle(packId).contains(word)

    fun addRecycle(packId: String, word: String) {
        val list = getRecycle(packId)
        if (!list.contains(word)) {
            list.add(0, word)
            prefs.edit().putString(keyRecycle(packId), list.joinToString("||")).apply()
        }
    }

    fun removeRecycle(packId: String, word: String) {
        val list = getRecycle(packId)
        list.remove(word)
        prefs.edit().putString(keyRecycle(packId), list.joinToString("||")).apply()
        setLearned(packId, word, false)
    }
}

@Preview(showBackground = true)
@Composable
fun VocabAppPreview() {
    PengPengEnglishTheme {
        VocabAppPreviewContent()
    }
}

@Composable
private fun VocabAppPreviewContent() {
    Text("PengPengEnglish Native Preview")
}

private enum class TopPage(val label: String) {
    HOME("首页"),
    STUDY("学习"),
    TOOLS("工具"),
    MINE("我的")
}

private enum class MyPageRoot(val title: String) {
    ROOT("我的"),
    PROFILE("个人资料"),
    STUDY_RECORD("学习记录"),
    FAVORITES("收藏内容"),
    SETTINGS("设置"),
    SETTING_GENERAL("通用设置"),
    SETTING_PLAYBACK("播放设置"),
    SETTING_PRIVACY("隐私设置"),
    ABOUT("关于我们"),
    VERSION_INFO("版本信息"),
    USER_AGREEMENT("用户协议"),
    PRIVACY_POLICY("隐私政策"),
    ACKNOWLEDGEMENTS("鸣谢"),
    DEVELOPER_INFO("开发者信息"),
    PHONE_INFO("手机信息")
}

@Composable
private fun MinePage(
    current: MyPageRoot,
    onNavigate: (MyPageRoot) -> Unit
) {
    val pageContext = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (current) {
                MyPageRoot.ROOT -> {
                    Text("我的")
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, ProfileActivity::class.java)) }) { Text("个人资料") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, StudyRecordActivity::class.java)) }) { Text("学习记录") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, FavoritesActivity::class.java)) }) { Text("收藏内容") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, VoiceDownloadActivity::class.java)) }) { Text("下载语音") }
                    TextButton(onClick = { onNavigate(MyPageRoot.SETTINGS) }) { Text("设置") }
                    TextButton(onClick = { onNavigate(MyPageRoot.ABOUT) }) { Text("关于我们") }
                }

                MyPageRoot.SETTINGS -> {
                    Text("设置")
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, GeneralSettingsActivity::class.java)) }) { Text("通用设置") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, PlaybackSettingsActivity::class.java)) }) { Text("播放设置") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, PrivacySettingsActivity::class.java)) }) { Text("隐私设置") }
                    TextButton(onClick = { onNavigate(MyPageRoot.ROOT) }) { Text("返回") }
                }

                MyPageRoot.ABOUT -> {
                    Text("关于我们")
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, VersionInfoActivity::class.java)) }) { Text("版本信息") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, UserAgreementActivity::class.java)) }) { Text("用户协议") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, PrivacyPolicyActivity::class.java)) }) { Text("隐私政策") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, AcknowledgementsActivity::class.java)) }) { Text("鸣谢") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, DeveloperInfoActivity::class.java)) }) { Text("开发者信息") }
                    TextButton(onClick = { pageContext.startActivity(Intent(pageContext, PhoneInfoActivity::class.java)) }) { Text("手机信息") }
                    TextButton(onClick = { onNavigate(MyPageRoot.ROOT) }) { Text("返回") }
                }

                else -> {
                    Text("页面已迁移到独立 XML 页面")
                    TextButton(onClick = { onNavigate(MyPageRoot.ROOT) }) { Text("返回我的") }
                }
            }
        }
    }
}
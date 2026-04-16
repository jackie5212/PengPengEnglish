import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/piper_model_option.dart';
import '../models/vocab_pack.dart';
import '../models/word_entry.dart';
import '../services/dictionary_db.dart';
import '../services/piper_tts_platform.dart';
import '../services/study_history_repository.dart';
class VocabTabBinder {
  VocabTabState? _state;

  void attach(VocabTabState s) => _state = s;

  void detach(VocabTabState s) {
    if (_state == s) _state = null;
  }

  Future<void> exitWordListToHome() async {
    await _state?.exitWordListToHome();
  }
}

class VocabTab extends StatefulWidget {
  const VocabTab({
    super.key,
    required this.dictionary,
    required this.progressPrefs,
    required this.studyHistory,
    required this.piper,
    required this.onInWordPageChanged,
    this.binder,
  });

  final DictionaryDb dictionary;
  final SharedPreferences progressPrefs;
  final StudyHistoryRepository studyHistory;
  final PiperTtsPlatform piper;
  final ValueChanged<bool> onInWordPageChanged;
  final VocabTabBinder? binder;

  @override
  State<VocabTab> createState() => VocabTabState();
}

class VocabTabState extends State<VocabTab> {
  static const _pageSize = 8;
  static const _searchPageSize = 5;

  bool _loading = true;
  List<VocabPack> _packs = [];
  VocabPack? _selectedPack;
  bool _inWordPage = false;
  int _currentPage = 0;
  String _statusText = 'Piper 初始化中…';
  bool _piperReady = false;
  String _selectedPiperModelId = PiperTtsPlatform.builtinAmy.id;
  List<PiperModelOption> _piperModels = [PiperTtsPlatform.builtinAmy];

  final _searchCtrl = TextEditingController();
  List<WordEntry> _searchResults = [];
  String _searchHint = '输入英文单词后点击搜索';
  int _searchTotal = 0;
  int _searchPage = 0;

  /// 避免 [FutureBuilder] 每次 rebuild 都 new Future，导致 SQLite 重复 open/close 在 Android 上报错（如 database is locked）。
  Future<int>? _packWordCountFuture;
  String? _packWordCountPackId;

  @override
  void initState() {
    super.initState();
    widget.binder?.attach(this);
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final packs = await widget.dictionary.getPacks();
    if (!mounted) return;
    if (packs.isEmpty) {
      setState(() {
        _loading = false;
        _packs = [];
      });
      return;
    }
    final savedId = widget.progressPrefs.getString('last_pack_id');
    final initial = packs.firstWhere(
      (p) => p.id == savedId,
      orElse: () => packs.first,
    );
    final inWord = widget.progressPrefs.getBool('last_in_word_page') ?? false;
    final page = widget.progressPrefs.getInt('last_page_${initial.id}') ?? 0;
    setState(() {
      _loading = false;
      _packs = packs;
      _selectedPack = initial;
      _inWordPage = inWord;
      _currentPage = page < 0 ? 0 : page;
      if (inWord) {
        _packWordCountPackId = initial.id;
        _packWordCountFuture = widget.dictionary.countWords(initial.id);
      }
    });
    widget.onInWordPageChanged(_inWordPage);
    await _refreshPiperModels();
    if (!mounted) return;
    setState(() {
      _piperReady = false;
      _statusText = '语音按需初始化（点击喇叭时再加载）';
    });
  }

  Future<void> _refreshPiperModels() async {
    final list = await widget.piper.availableModels();
    if (!mounted) return;
    setState(() => _piperModels = list.isEmpty ? [PiperTtsPlatform.builtinAmy] : list);
  }

  Future<void> _initPiper(String modelId) async {
    setState(() {
      _piperReady = false;
      _statusText = 'Piper 初始化中…';
    });
    final err = await widget.piper.init(modelId);
    if (!mounted) return;
    setState(() {
      _selectedPiperModelId = modelId;
      _piperReady = err == null;
      _statusText = err == null
          ? 'Piper 已就绪（${_labelForId(modelId)}）'
          : 'Piper 初始化失败：$err';
    });
  }

  String _labelForId(String id) {
    for (final m in _piperModels) {
      if (m.id == id) return m.label;
    }
    return id;
  }

  List<PiperModelOption> get _quickVoices =>
      _piperModels.where((m) => m.isSupported).take(3).toList();

  Future<void> _speakWithModel(PiperModelOption model, String text) async {
    if (!model.isSupported) {
      setState(() => _statusText = '模型不兼容：${model.label}');
      return;
    }
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    Future<void> doSpeak() async {
      setState(() => _statusText = 'Piper 播放中（${model.label}）');
      final err = await widget.piper.speak(trimmed);
      if (!mounted) return;
      if (err != null) {
        setState(() => _statusText = 'Piper 播放失败：$err');
      }
    }

    if (_selectedPiperModelId == model.id && _piperReady) {
      await doSpeak();
      return;
    }
    setState(() {
      _selectedPiperModelId = model.id;
      _piperReady = false;
      _statusText = 'Piper 初始化中（${model.label}）';
    });
    final err = await widget.piper.init(model.id);
    if (!mounted) return;
    if (err != null) {
      setState(() => _statusText = 'Piper 初始化失败：$err');
      return;
    }
    setState(() {
      _piperReady = true;
      _statusText = 'Piper 已就绪（${model.label}）';
    });
    await doSpeak();
  }

  Future<void> _persistProgress() async {
    final p = _selectedPack;
    if (p == null) return;
    await widget.progressPrefs.setString('last_pack_id', p.id);
    await widget.progressPrefs.setInt('last_page_${p.id}', _currentPage < 0 ? 0 : _currentPage);
    await widget.progressPrefs.setBool('last_in_word_page', _inWordPage);
  }

  Future<void> _recordStudyForCurrentPage() async {
    final p = _selectedPack;
    if (p == null || !_inWordPage) return;
    final totalWords = await widget.dictionary.countWords(p.id);
    final totalPages = ((totalWords + _pageSize - 1) / _pageSize).floor().clamp(1, 1 << 30);
    final idx = _currentPage.clamp(0, totalPages - 1);
    await widget.studyHistory.recordSession(p.id, p.name, idx, totalPages);
  }

  Future<void> _openDetail(WordEntry entry) async {
    final meaning = await widget.dictionary.lookupMeaning(entry.word);
    final phonetic = await widget.dictionary.lookupPhonetic(entry.word);
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          title: Row(
            children: [
              Expanded(child: Text(entry.word)),
              ..._quickVoices.map(
                (m) => TextButton(
                  onPressed: () => _speakWithModel(m, entry.word),
                  child: Text('▶${m.voiceInitial}'),
                ),
              ),
            ],
          ),
          content: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (phonetic.isNotEmpty) Text('/$phonetic/'),
                const SizedBox(height: 8),
                Text(meaning.isNotEmpty ? meaning : '未找到释义'),
                if (entry.example.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(entry.example),
                            if (entry.exampleCn.isNotEmpty) Text(entry.exampleCn),
                          ],
                        ),
                      ),
                      Column(
                        mainAxisSize: MainAxisSize.min,
                        children: _quickVoices
                            .map(
                              (m) => TextButton(
                                onPressed: () => _speakWithModel(m, entry.example),
                                child: Text('▶${m.voiceInitial}'),
                              ),
                            )
                            .toList(),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
          ],
        );
      },
    );
  }

  @override
  void dispose() {
    widget.binder?.detach(this);
    // 不在此 release Piper：切换底部 Tab 会 dispose 本页，但 Piper 由 MainShell 持有且应与 Activity 同生命周期。
    // dispose 里 fire-and-forget release 会与 MethodChannel 队列中的 speak/init 乱序，曾导致 native generate 崩溃。
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_packs.isEmpty || _selectedPack == null) {
      return const Center(child: Text('词库数据库为空或读取失败'));
    }

    final pack = _selectedPack!;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
          child: Text(_statusText, style: Theme.of(context).textTheme.bodySmall),
        ),
        Expanded(child: _inWordPage ? _buildWordList(pack) : _buildPackHome(pack)),
      ],
    );
  }

  Widget _buildPackHome(VocabPack pack) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('第一屏：请选择词库'),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: PopupMenuButton<VocabPack>(
                      onSelected: (v) async {
                        setState(() {
                          _selectedPack = v;
                          final pg = widget.progressPrefs.getInt('last_page_${v.id}') ?? 0;
                          _currentPage = pg < 0 ? 0 : pg;
                        });
                        await _persistProgress();
                      },
                      itemBuilder: (ctx) => _packs
                          .map((p) => PopupMenuItem(value: p, child: Text(p.name)))
                          .toList(),
                      child: Padding(
                        padding: const EdgeInsets.all(10),
                        child: Row(
                          children: [
                            Expanded(child: Text('当前词库：${pack.name}')),
                            const Icon(Icons.arrow_drop_down),
                          ],
                        ),
                      ),
                    ),
                  ),
                  FilledButton(
                    onPressed: () async {
                      try {
                        await _refreshPiperModels();
                        final page = widget.progressPrefs.getInt('last_page_${pack.id}') ?? 0;
                        if (!mounted) return;
                        setState(() {
                          _currentPage = page < 0 ? 0 : page;
                          _inWordPage = true;
                          _packWordCountPackId = pack.id;
                          _packWordCountFuture = widget.dictionary.countWords(pack.id);
                        });
                        widget.onInWordPageChanged(true);
                        await _persistProgress();
                        await _recordStudyForCurrentPage();
                        // 进入词库后先不做后台 preload：
                        // 某些机型在此时触发原生 TTS 初始化会导致进程级闪退（日志常见 QT file does not exist）。
                        // 用户点击发音时再按需 init/speak，优先保证稳定性。
                      } catch (e, st) {
                        debugPrint('进入词库失败: $e\n$st');
                        if (!mounted) return;
                        setState(() => _inWordPage = false);
                        widget.onInWordPageChanged(false);
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text('进入词库失败：$e')),
                        );
                      }
                    },
                    child: const Text('进入词库'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _searchCard(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _searchCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('查单词'),
            TextField(
              controller: _searchCtrl,
              decoration: const InputDecoration(labelText: '输入英文'),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                FilledButton(
                  onPressed: () async {
                    final kw = _searchCtrl.text.trim();
                    if (kw.isEmpty) {
                      setState(() {
                        _searchResults = [];
                        _searchTotal = 0;
                        _searchPage = 0;
                        _searchHint = '请输入要查询的英文单词';
                      });
                      return;
                    }
                    final total = await widget.dictionary.countSearchWords(kw);
                    final rows = await widget.dictionary.searchWords(
                      keyword: kw,
                      limit: _searchPageSize,
                      offset: 0,
                    );
                    if (!mounted) return;
                    setState(() {
                      _searchPage = 0;
                      _searchTotal = total;
                      _searchResults = rows;
                      _searchHint = total == 0 ? '未找到匹配单词' : '共找到 $total 条';
                    });
                  },
                  child: const Text('搜索'),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () {
                    _searchCtrl.clear();
                    setState(() {
                      _searchResults = [];
                      _searchTotal = 0;
                      _searchPage = 0;
                      _searchHint = '输入英文单词后点击搜索';
                    });
                  },
                  child: const Text('清空'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(_searchHint),
            if (_searchResults.isNotEmpty) _searchResultsList(),
          ],
        ),
      ),
    );
  }

  Widget _searchResultsList() {
    final totalPages = ((_searchTotal + _searchPageSize - 1) / _searchPageSize).floor().clamp(1, 1 << 30);
    return Column(
      children: [
        ..._searchResults.map(_wordCard),
        if (totalPages > 1)
          Row(
            children: [
              Expanded(
                child: FilledButton.tonal(
                  onPressed: _searchPage <= 0
                      ? null
                      : () async {
                          final next = _searchPage - 1;
                          final rows = await widget.dictionary.searchWords(
                            keyword: _searchCtrl.text.trim(),
                            limit: _searchPageSize,
                            offset: next * _searchPageSize,
                          );
                          if (!mounted) return;
                          setState(() {
                            _searchPage = next;
                            _searchResults = rows;
                          });
                        },
                  child: const Text('上一页'),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text('${_searchPage + 1}/$totalPages'),
              ),
              Expanded(
                child: FilledButton.tonal(
                  onPressed: _searchPage >= totalPages - 1
                      ? null
                      : () async {
                          final next = _searchPage + 1;
                          final rows = await widget.dictionary.searchWords(
                            keyword: _searchCtrl.text.trim(),
                            limit: _searchPageSize,
                            offset: next * _searchPageSize,
                          );
                          if (!mounted) return;
                          setState(() {
                            _searchPage = next;
                            _searchResults = rows;
                          });
                        },
                  child: const Text('下一页'),
                ),
              ),
            ],
          ),
      ],
    );
  }

  Widget _wordCard(WordEntry entry) {
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: InkWell(
        onTap: () => _openDetail(entry),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(entry.word, style: Theme.of(context).textTheme.titleSmall),
                    if (entry.example.isNotEmpty) Text(entry.example),
                  ],
                ),
              ),
              Column(
                children: _quickVoices
                    .map(
                      (m) => Padding(
                        padding: const EdgeInsets.only(bottom: 4),
                        child: TextButton(
                          onPressed: () => _speakWithModel(m, entry.word),
                          child: Text('▶${m.voiceInitial}'),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildWordList(VocabPack pack) {
    final countFut = _packWordCountFuture;
    if (countFut == null || _packWordCountPackId != pack.id) {
      return const Center(child: CircularProgressIndicator());
    }
    return FutureBuilder<int>(
      future: countFut,
      builder: (context, snap) {
        if (snap.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '加载词数失败：${snap.error}\n'
                '若曾快速切换界面，请稍候重试或完全退出应用后再打开。',
                textAlign: TextAlign.center,
              ),
            ),
          );
        }
        if (!snap.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final totalWords = snap.data!;
        final totalPages = ((totalWords + _pageSize - 1) / _pageSize).floor().clamp(1, 1 << 30);
        final safePage = _currentPage.clamp(0, totalPages - 1);
        return _VocabWordsPageView(
          key: ValueKey<String>('${pack.id}-$safePage'),
          dictionary: widget.dictionary,
          packId: pack.id,
          limit: _pageSize,
          offset: safePage * _pageSize,
          builder: (context, words) {
            return Column(
              children: [
                Expanded(
                  child: ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    itemCount: words.length,
                    itemBuilder: (ctx, i) => _wordCard(words[i]),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(16),
                  child: FilledButton.tonal(
                    onPressed: () async {
                      setState(() {
                        _currentPage = (safePage + 1) % totalPages;
                      });
                      await _persistProgress();
                      await _recordStudyForCurrentPage();
                    },
                    child: Text('${safePage + 1}/$totalPages'),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }

  /// 供外层系统返回键退出词库时调用
  /// 由 [VocabTabBinder] 或系统返回键触发。
  Future<void> exitWordListToHome() async {
    if (!_inWordPage) return;
    final p = _selectedPack;
    if (p != null) {
      final totalWords = await widget.dictionary.countWords(p.id);
      final totalPages = ((totalWords + _pageSize - 1) / _pageSize).floor().clamp(1, 1 << 30);
      final idx = _currentPage.clamp(0, totalPages - 1);
      await widget.studyHistory.recordSession(p.id, p.name, idx, totalPages);
    }
    setState(() {
      _inWordPage = false;
      _packWordCountFuture = null;
      _packWordCountPackId = null;
    });
    widget.onInWordPageChanged(false);
    await _persistProgress();
  }
}

/// 每个分页只持有一个 [getWordsPage] 的 Future，避免父组件 [setState] 时重复打开只读库。
class _VocabWordsPageView extends StatefulWidget {
  const _VocabWordsPageView({
    super.key,
    required this.dictionary,
    required this.packId,
    required this.limit,
    required this.offset,
    required this.builder,
  });

  final DictionaryDb dictionary;
  final String packId;
  final int limit;
  final int offset;
  final Widget Function(BuildContext context, List<WordEntry> words) builder;

  @override
  State<_VocabWordsPageView> createState() => _VocabWordsPageViewState();
}

class _VocabWordsPageViewState extends State<_VocabWordsPageView> {
  late final Future<List<WordEntry>> _pageFuture;

  @override
  void initState() {
    super.initState();
    _pageFuture = widget.dictionary.getWordsPage(widget.packId, widget.limit, widget.offset);
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<WordEntry>>(
      future: _pageFuture,
      builder: (context, snap) {
        if (snap.hasError) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text('加载单词列表失败：${snap.error}', textAlign: TextAlign.center),
            ),
          );
        }
        if (!snap.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        return widget.builder(context, snap.data!);
      },
    );
  }
}

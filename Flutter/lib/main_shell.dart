import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'screens/mine_page.dart';
import 'screens/placeholder_page.dart';
import 'screens/study_record_screen.dart';
import 'screens/version_info_screen.dart';
import 'screens/voice_download_screen.dart';
import 'services/dictionary_db.dart';
import 'services/piper_asset_bootstrap.dart';
import 'services/piper_tts_platform.dart';
import 'services/study_history_repository.dart';
import 'tabs/vocab_tab.dart';

enum _TopTab { home, study, tools, mine }

class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  final _vocabBinder = VocabTabBinder();
  final _piper = PiperTtsPlatform();

  DictionaryDb? _dictionary;
  StudyHistoryRepository? _study;
  SharedPreferences? _progressPrefs;
  Object? _loadError;

  _TopTab _tab = _TopTab.home;
  bool _inWordPage = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final db = await DictionaryDb.open();
      await PiperAssetBootstrap.ensureAmyBundled();
      final study = await StudyHistoryRepository.open();
      if (!mounted) return;
      setState(() {
        _progressPrefs = prefs;
        _dictionary = db;
        _study = study;
        _loadError = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loadError = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loadError != null) {
      return Scaffold(
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text('加载失败：$_loadError'),
          ),
        ),
      );
    }
    if (_dictionary == null || _study == null || _progressPrefs == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    return PopScope(
      canPop: _tab == _TopTab.home && !_inWordPage,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        if (_tab != _TopTab.home) {
          setState(() => _tab = _TopTab.home);
          return;
        }
        if (_inWordPage) {
          _vocabBinder.exitWordListToHome();
        }
      },
      child: Scaffold(
        body: SafeArea(
          child: _buildTabBody(),
        ),
        bottomNavigationBar: (_tab == _TopTab.home && _inWordPage)
            ? null
            : NavigationBar(
                selectedIndex: _tab.index,
                onDestinationSelected: (i) {
                  setState(() => _tab = _TopTab.values[i]);
                },
                destinations: const [
                  NavigationDestination(icon: Icon(Icons.home_outlined), label: '首页'),
                  NavigationDestination(icon: Icon(Icons.school_outlined), label: '学习'),
                  NavigationDestination(icon: Icon(Icons.build_outlined), label: '工具'),
                  NavigationDestination(icon: Icon(Icons.person_outline), label: '我的'),
                ],
              ),
      ),
    );
  }

  Widget _buildTabBody() {
    switch (_tab) {
      case _TopTab.home:
        return VocabTab(
          binder: _vocabBinder,
          dictionary: _dictionary!,
          progressPrefs: _progressPrefs!,
          studyHistory: _study!,
          piper: _piper,
          onInWordPageChanged: (v) => setState(() => _inWordPage = v),
        );
      case _TopTab.study:
        return const PlaceholderPage(title: '学习', message: '学习功能页（预留）');
      case _TopTab.tools:
        return const PlaceholderPage(title: '工具', message: '工具功能页（预留）');
      case _TopTab.mine:
        return MinePage(
          onOpenStudyRecord: () => Navigator.push<void>(
            context,
            MaterialPageRoute(builder: (_) => const StudyRecordScreen()),
          ),
          onOpenVoiceDownload: () => Navigator.push<void>(
            context,
            MaterialPageRoute(builder: (_) => const VoiceDownloadScreen()),
          ),
          onOpenVersion: () => Navigator.push<void>(
            context,
            MaterialPageRoute(builder: (_) => const VersionInfoScreen()),
          ),
        );
    }
  }
}

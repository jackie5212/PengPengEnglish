import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:path/path.dart' as p;

import 'native_paths.dart';

/// 将 `pubspec` 中 `assets/models/amy/` 下的文件同步到 `piper/amy_int8/`，供原生 Piper 读取。
class PiperAssetBootstrap {
  static const _prefix = 'assets/models/amy/';
  static const _expectedOnnx = 'en_US-amy-medium.onnx';
  static const _expectedOnnxAssetKey = '$_prefix$_expectedOnnx';

  /// 与 [PiperOnnxTtsManager] 一致且略高于 4KB，避免把空壳/指针文件当成有效模型。
  static const int _minOnnxBytes = 65536;
  static const int _minTokensBytes = 16;
  static const int _minEspeakFiles = 32;

  /// 与 [AmyAssetMaterializer] 内写入的版本一致；升高后旧安装会删掉 `amy_int8` 并重新部署（避免仍用未注入 metadata 的旧 onnx）。
  static const _amyBootstrapVersion = '5';

  static Future<void> ensureAmyBundled() async {
    final destRoot = await piperRuntimeRoot();
    final target = Directory(p.join(destRoot.path, 'amy_int8'));

    if (await _runtimeLooksValid(target) && await _bootstrapMarkerMatches(target)) return;

    if (await target.exists() && !await _bootstrapMarkerMatches(target)) {
      try {
        await target.delete(recursive: true);
      } catch (e) {
        throw Exception('无法清理旧 Amy 目录（语音包已升级，需全量刷新）${target.path}：$e');
      }
    }

    // Android：优先原生 AssetManager 流式拷贝大 onnx；失败或 espeak 仍不完整时回落到下方 Dart manifest 解压。
    if (defaultTargetPlatform == TargetPlatform.android) {
      try {
        const ch = MethodChannel('com.pengpengenglish/paths');
        await ch.invokeMethod<void>('materializeAmyVoice');
        if (await _runtimeLooksValid(target)) return;
      } catch (_) {
        // 部分 ROM 对 AssetManager.list 行为异常导致目录拷不全：忽略后继续走 Dart。
      }
    }

    if (target.existsSync()) {
      try {
        await target.delete(recursive: true);
      } catch (e) {
        throw Exception('无法清理旧目录 ${target.path}：$e');
      }
    }
    await target.create(recursive: true);

    final keys = await _listManifestKeys();
    final amyKeys = keys.where((k) => k.startsWith(_prefix)).toList()..sort();

    if (amyKeys.isEmpty) {
      throw Exception(
        '未找到 assets/models/amy/ 资源。请在 pubspec.yaml 的 flutter.assets 中加入 `assets/models/amy/`，并放入 Piper 完整文件。',
      );
    }

    if (!amyKeys.contains(_expectedOnnxAssetKey)) {
      final otherOnnx = amyKeys.where((k) {
        final lower = k.toLowerCase();
        return lower.endsWith('.onnx') && !lower.endsWith('.onnx.json');
      }).toList();
      throw Exception(
        otherOnnx.isEmpty
            ? '缺少真实语音模型文件：请将 Piper 的 $_expectedOnnx（体积通常数 MB 以上）放入 assets/models/amy/ 并重新构建。'
                ' 不能只有 en_US-amy-medium.onnx.json；若仓库用 Git LFS，请在本地 git lfs pull 后再拷贝。'
            : '缺少确切资源 $_expectedOnnxAssetKey（原生与校验均按此文件名读取）。'
                ' 当前 manifest 中的 .onnx 为：${otherOnnx.join(", ")} — 请改名为或额外放入 $_expectedOnnx。',
      );
    }

    for (final key in amyKeys) {
      final rel = key.substring(_prefix.length);
      if (rel.isEmpty) continue;
      final out = File(p.join(target.path, rel));
      await out.parent.create(recursive: true);
      final bd = await rootBundle.load(key);
      await out.writeAsBytes(bd.buffer.asUint8List(), flush: true);
    }

    if (!await _runtimeLooksValid(target)) {
      throw Exception(await _diagnoseInvalidAmy(target));
    }
    await _writeBootstrapMarker(target);
  }

  static Future<bool> _bootstrapMarkerMatches(Directory target) async {
    try {
      final f = File(p.join(target.path, '.pp_amy_bootstrap'));
      if (!await f.exists()) return false;
      return (await f.readAsString()).trim() == _amyBootstrapVersion;
    } catch (_) {
      return false;
    }
  }

  static Future<void> _writeBootstrapMarker(Directory target) async {
    await File(p.join(target.path, '.pp_amy_bootstrap')).writeAsString(_amyBootstrapVersion, flush: true);
  }

  static Future<String> _diagnoseInvalidAmy(Directory target) async {
    final onnx = File(p.join(target.path, _expectedOnnx));
    final tok = File(p.join(target.path, 'tokens.txt'));
    final esp = Directory(p.join(target.path, 'espeak-ng-data'));
    final onnxLen = await onnx.exists() ? await onnx.length() : 0;
    final tokLen = await tok.exists() ? await tok.length() : 0;
    final espFiles = await _countFilesUnder(esp);
    final onnxHint = await _onnxFailureHint(onnx, onnxLen);
    final rootOnnx = await _listOnnxBasenamesIn(target);
    final rootHint = rootOnnx.isEmpty
        ? ''
        : ' 目录内其它.onnx：${rootOnnx.join(", ")}。';
    return 'Amy 语音解压后仍无效：$onnxHint'
        'onnx($_expectedOnnx)=${onnxLen}B（需≥$_minOnnxBytes） tokens=${tokLen}B（需≥$_minTokensBytes） '
        'espeak内文件数=$espFiles（需≥$_minEspeakFiles）。$rootHint'
        '请 flutter clean 后全量重装；若 onnx 仅百余字节多为 Git LFS 指针，需拉取真实文件。';
  }

  static Future<String> _onnxFailureHint(File onnx, int onnxLen) async {
    if (onnxLen <= 0) return '未找到 $_expectedOnnx。';
    if (onnxLen >= _minOnnxBytes) return '';
    try {
      final n = onnxLen < 256 ? onnxLen : 256;
      final head = await onnx.openRead(0, n).first;
      final s = String.fromCharCodes(head);
      if (s.contains('git-lfs.github.com') || s.startsWith('version https://')) {
        return '当前 onnx 疑似 Git LFS 文本指针（非模型二进制）。';
      }
    } catch (_) {}
    return '';
  }

  static Future<List<String>> _listOnnxBasenamesIn(Directory target) async {
    if (!await target.exists()) return [];
    try {
      final out = <String>[];
      await for (final e in target.list(followLinks: false)) {
        if (e is! File) continue;
        final name = p.basename(e.path).toLowerCase();
        if (name.endsWith('.onnx') && !name.endsWith('.onnx.json')) {
          out.add(p.basename(e.path));
        }
      }
      return out;
    } catch (_) {
      return [];
    }
  }

  static Future<bool> _runtimeLooksValid(Directory target) async {
    final onnx = File(p.join(target.path, _expectedOnnx));
    final tok = File(p.join(target.path, 'tokens.txt'));
    final esp = Directory(p.join(target.path, 'espeak-ng-data'));
    if (!await onnx.exists() || await onnx.length() < _minOnnxBytes) return false;
    if (!await tok.exists() || await tok.length() < _minTokensBytes) return false;
    if (!await esp.exists()) return false;
    if (await _countFilesUnder(esp) < _minEspeakFiles) return false;
    return true;
  }

  static Future<int> _countFilesUnder(Directory dir) async {
    if (!await dir.exists()) return 0;
    try {
      var n = 0;
      await for (final e in dir.list(recursive: true, followLinks: false)) {
        if (e is File) {
          n++;
          if (n >= 4096) break;
        }
      }
      return n;
    } catch (_) {
      return 0;
    }
  }

  static Future<List<String>> _listManifestKeys() async {
    try {
      final man = await AssetManifest.loadFromAssetBundle(rootBundle);
      return man.listAssets().toList();
    } catch (_) {
      try {
        final raw = await rootBundle.loadString('AssetManifest.json');
        final map = json.decode(raw) as Map<String, dynamic>;
        return map.keys.cast<String>().toList();
      } catch (_) {
        return [];
      }
    }
  }
}

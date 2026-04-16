import 'package:flutter/services.dart';

import '../models/piper_model_option.dart';

/// 与 Android 原生 `MethodChannel` 约定一致（见 docs/android-feature-parity.md）。
class PiperTtsPlatform {
  PiperTtsPlatform() : _ch = const MethodChannel('com.pengpengenglish/piper_tts');

  final MethodChannel _ch;

  /// 内置 Amy（与未接 Channel 时的占位一致）
  static const PiperModelOption builtinAmy = PiperModelOption(
    id: 'amy_int8',
    label: 'Amy (medium-int8)',
  );

  Future<List<PiperModelOption>> availableModels() async {
    try {
      final raw = await _ch.invokeMethod<List<dynamic>>('availableModels');
      if (raw == null) return [builtinAmy];
      return raw.map((e) {
        final m = Map<String, dynamic>.from(e as Map);
        return PiperModelOption(
          id: m['id'] as String,
          label: m['label'] as String,
          isSupported: m['isSupported'] as bool? ?? true,
        );
      }).toList();
    } catch (_) {
      return [builtinAmy];
    }
  }

  Future<String?> init(String modelId) async {
    try {
      await _ch.invokeMethod<void>('init', {'modelId': modelId});
      return null;
    } on PlatformException catch (e) {
      final m = e.message;
      if (m != null && m.isNotEmpty) return m;
      return switch (e.code) {
        'unknown_model' => '未知模型：$modelId',
        'bad_args' => '参数错误：modelId',
        'no_piper' => 'Piper 未就绪（原生侧）',
        _ => e.code,
      };
    } catch (e) {
      return e.toString();
    }
  }

  Future<String?> speak(String text) async {
    try {
      await _ch.invokeMethod<void>('speak', {'text': text});
      return null;
    } on PlatformException catch (e) {
      return e.message ?? e.code;
    } catch (e) {
      return e.toString();
    }
  }

  Future<void> release() async {
    try {
      await _ch.invokeMethod<void>('release');
    } catch (_) {}
  }

  Future<void> preload(List<String> modelIds) async {
    try {
      await _ch.invokeMethod<void>('preload', {'modelIds': modelIds});
    } catch (_) {}
  }
}

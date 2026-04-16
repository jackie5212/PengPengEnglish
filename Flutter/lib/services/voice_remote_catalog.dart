/// 与 `VoiceDownloadActivity` 中常量一致。
const String kModelIndexUrl = 'http://47.97.36.224/tts/model.txt';
const String kModelBaseUrl = 'http://47.97.36.224/tts';
const String kVersionUrl = 'http://47.97.36.224/tts/version.txt';

class RemoteVoiceModel {
  RemoteVoiceModel({required this.displayName, required this.fileName});

  final String displayName;
  final String fileName;

  String get id => fileName.replaceAll('.tar.bz2', '');
}

/// 解析 model.txt：`[显示名]` 下一行为 `xxx.tar.bz2`。
List<RemoteVoiceModel> parseModelIndex(String raw) {
  final lines = raw.split(RegExp(r'\r?\n')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
  final out = <RemoteVoiceModel>[];
  String? pendingName;
  for (final line in lines) {
    if (line.startsWith('#')) continue;
    final isBracket = line.startsWith('[') && line.endsWith(']') && line.length > 2;
    if (isBracket) {
      pendingName = line.substring(1, line.length - 1).trim();
      continue;
    }
    if (!line.endsWith('.tar.bz2')) continue;
    final display = (pendingName != null && pendingName.isNotEmpty)
        ? pendingName
        : line.replaceAll('.tar.bz2', '');
    out.add(RemoteVoiceModel(displayName: display, fileName: line));
    pendingName = null;
  }
  return out;
}

class PiperModelOption {
  const PiperModelOption({
    required this.id,
    required this.label,
    this.isSupported = true,
  });

  final String id;
  final String label;
  final bool isSupported;

  String get shortLabel {
    final s = label.split(' (').first.split(' ').first;
    return s.isEmpty ? label : s;
  }

  String get voiceInitial {
    final t = shortLabel.trim();
    if (t.isEmpty) return '?';
    return t[0].toUpperCase();
  }
}

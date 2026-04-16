import 'package:flutter_test/flutter_test.dart';

import 'package:pengpeng_english/app_root.dart';

void main() {
  testWidgets('MaterialApp 能构建', (WidgetTester tester) async {
    await tester.pumpWidget(const PengPengEnglishApp());
    await tester.pump();
    expect(find.byType(PengPengEnglishApp), findsOneWidget);
  });
}

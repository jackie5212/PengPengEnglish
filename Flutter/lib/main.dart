import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'app_root.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    developer.log(
      details.exceptionAsString(),
      stackTrace: details.stack,
      name: 'FlutterError',
    );
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    developer.log(error.toString(), stackTrace: stack, name: 'PlatformDispatcher');
    return true;
  };
  runApp(const PengPengEnglishApp());
}

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/screens/welcome_screen.dart';
import 'package:droiddesk/screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  DroidDeskPlatform.init();

  runApp(
    ChangeNotifierProvider(
      create: (_) => AppState(),
      child: const DroidDeskApp(),
    ),
  );
}

class DroidDeskApp extends StatefulWidget {
  const DroidDeskApp({super.key});

  @override
  State<DroidDeskApp> createState() => _DroidDeskAppState();
}

class _DroidDeskAppState extends State<DroidDeskApp> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().initialize();
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DroidDesk',
      debugShowCheckedModeBanner: false,
      theme: DroidTheme.themeData,
      home: Consumer<AppState>(
        builder: (context, state, _) {
          if (state.isSetupComplete) {
            return const HomeScreen();
          }
          return const WelcomeScreen();
        },
      ),
    );
  }
}

cd app
adb uninstall com.duy.asciiart

adb uninstall asciigenerator.pro

adb install -r app-free-release.apk

adb install -r app-pro-release.apk

adb shell am start -n "com.duy.asciiart/com.duy.ascii.sharedcode.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
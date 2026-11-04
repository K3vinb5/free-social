# Reverse-engineering recipe (SocialLite 3.0.1)

Source artifact: `/home/kevin/Downloads/Sociallite+-Block+Reels&Shorts_3.0.1_APKPure.xapk`
(base APK + `config.arm64_v8a` + `config.xxhdpi` splits; package `com.sociallite.android`,
versionCode 258, minSdk 32, targetSdk 36).

Everything lives in the workspace under `reverse/` (gitignored, ~650 MB — regenerated, not
committed). A `/tmp/opencode/<name>` symlink back to `reverse/<name>` exists only so in-flight
agents with old paths keep working; treat `reverse/` as canonical.

Layout: `reverse/tools` (jadx + apktool jars), `reverse/xapk` (unpacked APKs),
`reverse/dec` (decompiled sources), `reverse/rd*` (throwaway single-class dumps).

No `jadx`/`apktool` in the distro repos, so tools are fetched as jars/zips:

```sh
mkdir -p reverse/tools && cd reverse/tools
curl -sL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
unzip -o -q jadx.zip -d jadx
curl -sL -o apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.11.1/apktool_2.11.1.jar
```

Unpack + decompile:

```sh
mkdir -p reverse/xapk && cd reverse/xapk
unzip -o -q "/home/kevin/Downloads/Sociallite+-Block+Reels&Shorts_3.0.1_APKPure.xapk"

# resources + readable AndroidManifest + assets
java -jar ../tools/apktool.jar d -f -s -o ../dec/apktool com.sociallite.android.apk

# Java sources (6446 classes, ~90 s; 59 known decompile errors)
../tools/jadx/bin/jadx -j 6 --no-debug-info -d ../dec/jadx com.sociallite.android.apk

# smali = ground truth for methods jadx gives up on
java -jar ../tools/apktool.jar d -f -o ../dec/smali --no-res com.sociallite.android.apk
```

Recovering a method jadx refuses to decompile (emits
`throw new UnsupportedOperationException("Method not decompiled: ...")`):

```sh
reverse/tools/jadx/bin/jadx --no-res --show-bad-code \
  --single-class com.sociallite.android.data.AppBlockerService \
  --single-class-output reverse/dec/bad/AppBlockerService.java \
  reverse/xapk/com.sociallite.android.apk
```

`--show-bad-code` prints structured pseudo-Java for exactly the method that failed
(e.g. `AppBlockerService.enforcePackage`, 946 smali instructions). Cross-check anything
ambiguous against `reverse/dec/smali/smali*/`.

## Obfuscation shape

R8 full mode: only the entry points referenced from the manifest (and a handful of
`@SerialName`-style boundaries) keep their names — 35 classes under
`com/sociallite/android/**`. Everything else lives in 1–2 letter packages
(`ie/`, `ue/`, `ve/`, `x2/`, `w8/`, `y8/`, `s1/`, `ka/`, `gb/`, `c/`) with Kotlin
inline classes, lambdas and Compose under renamed names. Cross-references survive,
so read entry point → follow types.

## Orientation cheatsheet

| Path | What |
| --- | --- |
| `reverse/dec/apktool/AndroidManifest.xml` | readable manifest (services, permissions, widgets) |
| `reverse/dec/apktool/res/xml/*` | accessibility service configs, device admin, widget info |
| `reverse/dec/apktool/res/values/strings.xml` | user-facing copy → feature names |
| `reverse/dec/apktool/assets/*.js` | WebView-injected content filters |
| `reverse/dec/jadx/sources/com/sociallite/android/data/*` | the enforcement entry points |
| `reverse/dec/smali/smali*/` | smali, for the 59 classes jadx failed on |

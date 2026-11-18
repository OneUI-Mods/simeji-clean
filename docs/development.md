# 開発・解析

## プロジェクト

開発フォルダは `~/Documents/SimejiClean`。パッケージは所有ドメインryuya-dev.netに基づく `net.ryuya_dev.simejiclean`。One UI Home Tweaksとは独立したアプリです。

JDK 27、Android SDK 37.2、Gradle Wrapperを使用します。

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

鍵のない通常のビルド・CIは未署名release APKを作ります。公開するAPKはdebug版ではなく、リポジトリ外の専用鍵で署名します。

```sh
SIMEJI_CLEAN_SIGNING_PROPERTIES=/absolute/path/to/private-signing.properties \
  ./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

propertiesには`storeFile`、`storePassword`、`keyAlias`、`keyPassword`を指定します。鍵・設定ファイル・端末APK・逆コンパイル結果・画面・私用ログはGitへ追加しません。依存関係とライセンス一覧が一致しなければビルドを停止します。

## 解析した経路

Simeji 23.6.1で、白いPR一覧は`jp.baidu.simeji.ad.sug.pop.SugPopup`。`layout_sug_view_container`から白い`ListView`とスライド操作部を作り、アプリの`SimejiPopupWindow`でキーボードの上に表示します。通常の変換候補とは別です。

`PopupManager.popupNext(IPopup)`はUIイベントへ渡し、`EventManager.popup(IPopup)`は`PopupManager.checkPopup(IPopup)`の戻り値を評価します。実APKのFLAG_CANCELは3で、その場合は表示・遅延・置き換えを行いません。対象のポップアップかつ現在のEditorInfo.packageNameが`com.android.vending`のときだけ、その標準の拒否値を返します。別のポップアップや入力先には元の判定を使います。

## 動的検索

- IME実装はマニフェストの`BIND_INPUT_METHOD`サービスから取得し、実際の入力サービスのプロセスだけで導入します。複数ある場合は現在選択中のIMEコンポーネントで絞り、一意にならなければ停止します。
- `layout_sug_view_container`のリソースIDをその版のResourcesから取得します。数値を固定しません。
- DEXから、当該レイアウトをinflateし、`showAtLocation(View,int,int,int)`を呼び、ListViewを保持するポップアップを検索します。
- そのポップアップのインターフェース、Stackを持つ管理クラス、整数の判定メソッド、`mPopups empty.`と`priority FLAG_CANCEL.`の目印を組み合わせます。
- nativeのキャンセル定数、クラス指定の終了処理、単一インスタンスの取得処理も型と実際の呼び出しから特定します。
- 判定メソッドの直接の呼び出し元をDEXで列挙し、実メソッドをdeoptimizeします。候補重複・呼び出し元なし・過大な候補集合は拒否します。
- 元のクラス名、メソッド名、フィールド名は検出結果です。固定名の失敗後に似たビューを一律に隠す処理はありません。
- baseとDEXを含むsplit APKが対象。APKの場所・サイズ・更新時刻・DEXエントリCRC／サイズ・リソースID・スキーマからキャッシュキーを作ります。キーが変われば再検索し、キャッシュから戻す場合もreflectionの構造を照合します。

`DexPopupDiscovery`はAndroidに依存しない解析器、`PopupDiscovery`は端末Resources・キャッシュ・reflectionとの接続です。`tools/InspectApk.java`で手元のAPKをオフライン検査できます。Samsung/Simejiの逆コンパイル結果そのものは含みません。

## 設定と安全性

設定Providerはモジュール自身かSimejiのUIDだけが利用できます。入力文字列・InputConnection・候補文字列を取得せず、入力先のパッケージ名だけを参照します。設定はContentObserverと入力ビュー開始イベントで読み直し、定期ポーリングはしません。

新規インストール直後などにProviderを取得できない場合は広告を隠さず、実際の入力開始イベントで再試行します。フック失敗時は状態を表示し、標準の入力処理を続行します。状態報告にはソース由来のbuild tokenと同じ端末起動かを示す時刻を付け、古いビルドの報告を有効な現在の状態として表示しません。

クラッシュ保護は同じ起動・同じ設定世代・2分未満の前回プロセスについて、Androidの終了理由・プロセス名・終了時刻を照合します。Java/nativeクラッシュ3回で保護を保持し、設定の解除世代が更新されるまで継続します。強制終了・古いPID・再起動後の記録を新しいクラッシュに数えません。実際の端末で連続クラッシュを強制発生させる試験はしていません。

```sh
adb logcat -s SimejiClean:I '*:S'
```

ログは`DISCOVERY_FOUND`、`DISCOVERY_CACHE_HIT`、`READY`、`STATE blocked/off/ready`、`CONFIG_UNAVAILABLE`、`DISCOVERY_STOP`などで確認します。

[Build environment / ビルド環境](build-environment.md)

# MyAdBlocker

Android 9 (API 28) 以上で動作する、Root不要のローカルVPN型DNS広告ブロッカーです。  
端末内の仮想VPNインターフェース（`VpnService`）でDNS通信（UDP:53）だけをインターセプトして広告・トラッキングドメインをブロックし、通常の動画・Web通信はOS標準経路を素通しさせることで、高速・省電力に動作します。

## 主な機能

- **ローカルDNS広告ブロック**
  - 内蔵の日本向け・主要広告ネットワークドメインリストに加え、公開リストの取り込みに対応
  - ブロック対象は `0.0.0.0` / `::`（その他レコードは `NXDOMAIN`）を即返却、通常通信は `1.1.1.1` へ非同期フォワード
- **クイック設定パネル（Quick Settings Tile）連携**
  - 通知シェードの「広告ブロック」タイルから1タップでON/OFF切り替え
- **タイマー付き一時停止（30秒 / 1分 / 3分）**
  - VPN接続を切らずに一時的にブロック判定をスキップし、タイマー終了後に自動再開
  - 通知のアクションボタンからも「1分一時停止」「今すぐ再開」が可能
- **リアルタイム全DNSログ ＆ ワンタップルール追加**
  - すべてのDNS問い合わせ（BLOCK / PASS）をリアルタイム表示し、検索・フィルタ・1タップでの許可/ブロック追加に対応
- **公開フィルタURL読み込み ＆ カスタムルール**
  - `AdGuard DNS Filter` / `280blocker` / `StevenBlack Hosts` のプリセット取得および任意のURL読み込み（hosts形式・AdGuard DNS形式 `||domain^`・プレーンドメイン形式に対応）
  - ホワイトリスト（許可）と手動ブラックリストの管理（ホワイトリスト優先）
- **アプリ別除外設定（スプリットトンネリング）**
  - `addDisallowedApplication` により、選択したアプリをVPN対象から除外（切り替え時は自動再適用）

## 技術構成

- **言語 / UI**: Kotlin / Jetpack Compose (Material 3)
- **主要Android API**:
  - `android.net.VpnService`
  - `android.service.quicksettings.TileService`
  - Kotlin Coroutines / `StateFlow`

## ビルド方法

1. Android Studio（または JDK 17 / 21 + Android SDK）を用意します。
2. リポジトリルートでデバッグAPKをビルドします：
   ```bash
   ./gradlew assembleDebug
   ```
3. ユニットテストの実行：
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. リリースビルドに署名する場合は、ルートに `keystore.properties` を作成してください：
   ```properties
   storeFile=keystore/release.jks
   storePassword=your_store_password
   keyAlias=your_alias
   keyPassword=your_key_password
   ```

## 注意事項・制限

- Android OSの仕様上、他のVPNアプリとの同時併用はできません。
- Chrome等の「セキュアDNS（DoH）」が有効になっている場合、OSのDNSを経由せずバイパスされることがあります。
- YouTube公式アプリなど、動画本体と同一ドメイン・同一ストリームから配信される広告はDNS方式ではブロックできません。

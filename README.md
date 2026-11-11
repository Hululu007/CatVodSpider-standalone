# CatVodSpider-standalone

TVBox / 影视仓 用的独立爬虫工程。纯 Java 编写、零第三方依赖，编译产物是一个内含 `classes.dex` 的 `spider.jar`，可直接被宿主用 `DexClassLoader` 加载。

直接下载发行版即可使用内置线路，国内访问https://gongchuang.pages.dev/
```

## 特性

- **零依赖**：不使用 gson / okhttp / retrofit，只用 JDK 自带类 + `org.json`（由宿主或 `android.jar` 提供）
- **双模式打包**：同一个 jar 里同时放 `classes.dex`（给 TVBox 加载）和 `.class`（给 JVM 调试），两边一致
- **不依赖宿主 Bean**：不引用任何宿主侧的 `com.github.catvod.crawler` 之外的类，任何支持 dex jar 的分支都能跑
- **自带 HTTP 客户端**：`Http.java` 自己建 Socket、放宽证书、DNS 多 IP 并行竞速、手写 chunked / gzip 解析，绕开 Cloudflare 不主动断连导致的挂起
- **三级缓存**：页面级、播放结果级、搜索级，重复点击基本秒开
- **异步预热**：DNS 预解析 + 首分片预取，缩短开播等待

## 站点列表

| key | 名称 | 类 | 站点 | 覆盖 | 备注 |
|---|---|---|---|---|---|
| `czzy` | 厂长资源 | `Czzy` | 4kcz.com | 全类型 | WordPress + mibt，站前有雷池 WAF |
| `sotvla` | SOTVLA | `Sotvla` | sotvla.cc | 全类型 | 聚合搜索站，播放交给第三方解析 |
| `appmovie` | APP影院 | `Appmovie` | appmovie.art | 全类型 | 标准苹果CMS |
| `xifanys` | 稀饭影视 | `Xifanys` | xifanys.com | 全类型 | 苹果CMS + mxtheme |
| `ppnix` | PPnix | `Ppnix` | ppnix.com | 电影 / 剧 | 自建站，`/info/m3u8/{id}/{param}.m3u8` |

> `Olevod.java`（欧乐影院）已编译进 jar 但未在 `tv.json` 中启用，需要时加一条站点配置即可。

## 目录结构

```
.
├── build.bat                    一键构建脚本（编译 → dex → 打包 → 算 MD5 → 改 tv.json）
├── tv.json                      站点配置，供 TVBox 加载
├── spider.jar                   成品（含 classes.dex + .class）
├── dist/
│   ├── spider.jar               构建输出，与根目录一致
│   └── spider.jar.md5           MD5，与 tv.json 里的校验值对应
├── src/com/github/catvod/
│   ├── crawler/Spider.java      基类占位实现
│   └── spider/
│       ├── Init.java            宿主加载 jar 时调用，弹 Toast 确认加载成功
│       ├── Http.java            零依赖 HTTP 客户端
│       ├── Czzy.java            厂长资源
│       ├── Sotvla.java          SOTVLA
│       ├── Appmovie.java        APP影院
│       ├── Xifanys.java         稀饭影视
│       ├── Ppnix.java           PPnix
│       └── Olevod.java          欧乐影院（备选，默认未启用）
└── test/                        调试用 main 类，每个站点一组
```

## 构建

### 依赖

- **JDK 8+**（`build.bat` 会自动在 `JAVA_HOME`、`Program Files\Java`、`Program Files\Eclipse Adoptium` 等位置查找）
- **Android SDK**：需要 `build-tools`（含 `d8`）和 `platforms`（含 `android.jar`）

  ```
  set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
  ```

### 执行

```bat
build.bat
```

脚本做五件事：

1. `javac --release 8` 编译 `src/**/*.java` 到 `out/`
2. `jar cf classes.jar -C out .` → `d8 --release --min-api 21` 生成 `dex/classes.dex`
3. 把 `classes.dex` 和全部 `.class` 打进 `dist/spider.jar`
4. 计算 MD5 写入 `dist/spider.jar.md5`
5. 覆盖根目录 `spider.jar`，并正则替换 `tv.json` 里的 `;md5;xxxxxxxx`

`--min-api 21` 对应 Android 5.0；`--release 8` 是 Java 8 语法级别，两者要匹配。

### 手动构建（等价步骤）

```bash
javac --release 8 -encoding UTF-8 -cp "$ANDROID_HOME/platforms/android-37.0/android.jar" \
      -d out $(find src -name '*.java')
jar cf classes.jar -C out .
d8 --release --min-api 21 --lib "$ANDROID_HOME/platforms/android-37.0/android.jar" \
   --output dex classes.jar
cp dex/classes.dex dist/
jar cf dist/spider.jar -C dist classes.dex -C out .
rm dist/classes.dex
```

## 配置

`tv.json` 结构：

```json
{
  "version": 30,
  "name": "共创影视",
  "spider": "https://ghproxy.net/https://raw.githubusercontent.com/gongchuang1089/CatVodSpider-standalone/refs/heads/main/spider.jar?v=30;md5;<32位MD5>",
  "sites": [
    {
      "key": "czzy",
      "name": "厂长资源",
      "type": 3,
      "api": "csp_Czzy",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1
    }
  ]
}
```

- `spider` 后面的 `?v=30` 用于破缓存，`;md5;` 后面的值必须是 jar 的真实 MD5，否则部分宿主会拒绝加载
- `type: 3` 表示 csp 类（jar 内爬虫）；`api: csp_<类名>` 对应 `com.github.catvod.spider.<类名>`

### 各站点 `ext` 参数

| 站点 | ext 示例 | 说明 |
|---|---|---|
| `czzy` / `sotvla` / `appmovie` / `xifanys` / `ppnix` | `{"host":"https://..."}` | 统一支持用 `host` 覆盖站点域名，站点换域名时不用改代码 |

`ext` 也支持直接传字符串形式的 host：`"ext": "https://www.example.com"`。

## 发布流程

1. 改代码 → 跑 `build.bat`
2. 上传 `spider.jar` 和 `tv.json` 两个文件到仓库根目录
3. TVBox 里清空配置缓存 → 重新加载

如果 `tv.json` 里的 `spider` 指向 GitHub，地址形如：

```
https://raw.githubusercontent.com/gongchuang1089/CatVodSpider-standalone/main/spider.jar?v=30;md5;<MD5>
```

## 各站点技术要点

### 欧乐影院（Olevod）

官方接口带 `_vv` 参数，是对秒级时间戳做的位混淆：取 `md5(ts)` 的片段，与 `ts` 每个字符的二进制位交叉拼接成 hex3。只取 `vip=false` 的免费直链，因此不进网页播放器，也就没有广告。

### 苹果CMS 类站点（Appmovie / Xifanys / Czzy 等）

统一走采集接口：

```
列表  {api}?ac=videolist&t={type_id}&pg={page}
搜索  {api}?ac=videolist&wd={关键词}
详情  {api}?ac=detail&ids={vod_id}
```

`vod_play_url` 格式是 `第1集$地址#第2集$地址`，多线路用 `$$$` 分隔。

## 调试

`test/` 下每个站点有对应的 `Test*.java`，都是带 `main` 的普通类，直接用 JVM 跑：

```bash
javac -encoding UTF-8 -cp "out;android.jar;json-20250517.jar" -d testout test/TestCzzy.java
java -Dfile.encoding=UTF-8 -cp "out;testout;json-20250517.jar" TestCzzy
```

注意：`android.jar` 里的 `org.json` 是空壳实现，运行期必须换成真正的 `org.json` jar（例如 `json-20250517.jar`），否则会抛 `Stub!`。

`Init.java` 里有一个 Toast「By共创已加载」，用于确认 jar 是否真的被宿主加载。排查完可以删掉。

## 约束与约定

- 只允许使用 `java.*`、`javax.*`、`org.json.*`，不引第三方库
- 所有方法都不向外抛异常，统一用 `error(msg)` 返回 `{"list":[],"msg":"..."}`
- 网络请求一律 `trustAll`（TVBox 旧设备缺新根证书，严格校验必然失败），这是有意的妥协
- 状态码非 200、JSON 解析失败、字段缺失都要有兜底，宁可返回空列表也不能崩
- 新增站点类名使用 `csp_<类名>` 方式挂到 `tv.json`，`key` 避免数字开头（部分宿主解析异常）

## 更新日志

| 版本 | 内容 |
|---|---|
| v30 | **移除 4K / 聚合 / 青花 / 两个BT / 茶杯狐 五个接口**，jar 体积从 181 KB 降到 85 KB，仅保留 5 个站点 |
| v29 | 修复 `vod_id` 含竖线导致搜索结果被宿主丢弃；聚合分类由 40+ 精简到 12 个；线路顺序按配置固定 |
| v28 | 4K 线路性能优化：gzip、页面/播放/搜索三级缓存、DNS 预热、首分片预取 |
| v27 | 新增聚合站 `agg`（10 个采集源多线路） |
| v26 | 新增 4K 站 `k4k`（4kvm.net，支持 4K 档位） |
| v25 | 初始版本，8 个站点 |

## 免责声明

本项目仅用于技术研究与个人学习，抓取的内容版权归原站点所有。请勿用于商业用途，使用产生的后果由使用者自行承担。

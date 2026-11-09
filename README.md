# CatVodSpider-standalone

TVBox / 影视仓 用的独立爬虫工程。纯 Java 编写、零第三方依赖，编译产物是一个内含 `classes.dex` 的 `spider.jar`，可直接被宿主用 `DexClassLoader` 加载。

当前配置版本 **v29**，包含 10 个站点（含 1 个聚合站）。在tvbox放入接口https://ghproxy.net/https://raw.githubusercontent.com/gongchuang1089/CatVodSpider-standalone/refs/heads/main/tv.json即可使用。

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
| `k4k` | 4K | `Kfourkvm` | 4kvm.net | 全类型 + 4K | 与 bttwo 同源模板，HMAC 签名取流 |
| `agg` | 聚合 | `MacAgg` | 10 个采集源 | 剧 / 综 / 漫 / 电影 | 一部剧多条线路，可切换源 |
| `qinghuaci` | 青花（1k秒播放） | `Qinghuaci` | cj1nym.com | 全类型 | 自研 JSON API，`sha1(md5(...))` 签名 |
| `czzy` | 厂长资源 | `Czzy` | 4kcz.com | 全类型 | WordPress + mibt，站前有雷池 WAF |
| `bttwo` | 两个BT | `Bttwo` | bttwo.life | 全类型 | nbmovie 模板，HMAC + XOR 签名 |
| `chabeihu` | 茶杯狐 | `Chabeihu` | chabeihu.com.cn | 全类型 | 苹果CMS，也被青花瓷作聚合线路调用 |
| `sotvla` | SOTVLA | `Sotvla` | sotvla.cc | 全类型 | 聚合搜索站，播放交给第三方解析 |
| `appmovie` | APP影院 | `Appmovie` | appmovie.art | 全类型 | 标准苹果CMS |
| `xifanys` | 稀饭影视 | `Xifanys` | xifanys.com | 全类型 | 苹果CMS + mxtheme |
| `ppnix` | PPnix | `Ppnix` | ppnix.com | 电影 / 剧 | 自建站，`/info/m3u8/{id}/{param}.m3u8` |

> `Olevod.java`（欧乐影院）已编译进 jar 但未在 `tv.json` 中启用，需要时加一条站点配置即可。

### 聚合站（`agg`）说明

把 10 个苹果CMS 采集源合成同一个站点，进详情页后播放器里会出现多条线路，每条对应一个源：

```
360资源 · 暴风资源 · 非凡资源 · 量子资源 · 电影天堂
光速资源 · 魔都资源 · 闪电资源 · 无尽资源 · 百度资源
```

- 顺序按实测响应速度排，最快的排第一，失败的源自动跳过
- 并发用「渐进收集」：谁先返回先收谁，到预算时间就收工，慢站不拖累整体
- 播放地址两种形态都处理：直链（`.m3u8`）直接返回；`share` 页则二次请求取 `var main="/...index.m3u8?sign=..."` 拼直链
- 首页分类精简为 12 个常用分类，避免分类条过长

## 目录结构

```
.
├── build.bat                    一键构建脚本（编译 → dex → 打包 → 算 MD5 → 改 tv.json）
├── tv.json                      站点配置，供 TVBox 加载
├── spider.jar                   成品（含 classes.dex + .class）
├── spider-old-*.jar             历史版本备份
├── dist/
│   ├── spider.jar               构建输出，与根目录一致
│   └── spider.jar.md5           MD5，与 tv.json 里的校验值对应
├── src/com/github/catvod/
│   ├── crawler/Spider.java      基类占位实现
│   └── spider/
│       ├── Init.java            宿主加载 jar 时调用，弹 Toast 确认加载成功
│       ├── Http.java            零依赖 HTTP 客户端
│       ├── Kfourkvm.java        4K 站点
│       ├── MacAgg.java          聚合站点
│       └── ...                  其余站点
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
javac --release 8 -encoding UTF-8 -cp "$ANDROID_HOME/platforms/android-36/android.jar" \
      -d out $(find src -name '*.java')
jar cf classes.jar -C out .
d8 --release --min-api 21 --lib "$ANDROID_HOME/platforms/android-36/android.jar" \
   --output dex classes.jar
cp dex/classes.dex dist/
jar cf dist/spider.jar -C dist classes.dex -C out .
rm dist/classes.dex
```

## 配置

`tv.json` 结构：

```json
{
  "version": 29,
  "name": "共创影视",
  "spider": "https://gitee.com/gongchuang666/tvbox/raw/master/spider.jar?v=29;md5;<32位MD5>",
  "sites": [
    {
      "key": "k4k",
      "name": "4K",
      "type": 3,
      "api": "csp_Kfourkvm",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1,
      "ext": "{\"quality\":\"auto\"}"
    }
  ]
}
```

- `spider` 后面的 `?v=29` 用于破缓存，`;md5;` 后面的值必须是 jar 的真实 MD5，否则部分宿主会拒绝加载
- `type: 3` 表示 csp 类（jar 内爬虫）；`api: csp_<类名>` 对应 `com.github.catvod.spider.<类名>`

### 各站点 `ext` 参数

| 站点 | ext 示例 | 说明 |
|---|---|---|
| `k4k` | `{"quality":"auto"}` | `auto` / `4k` / `1080` / `720`；`cookie` 可填 VIP 登录态解锁 4K |
| `agg` | `{"timeout":8000}` | `timeout` 单源超时毫秒；`sources` 只启用指定源；`cookie` 透传 |
| `qinghuaci` | `{"aggregate":false}` | `aggregate` 是否在详情里追加茶杯狐聚合线路 |
| `czzy` / `bttwo` / `chabeihu` / `sotvla` / `appmovie` / `xifanys` / `ppnix` | `{"host":"https://..."}` | 统一支持用 `host` 覆盖站点域名，站点换域名时不用改代码 |

`ext` 也支持直接传字符串形式的 host：`"ext": "https://www.example.com"`。

## 发布流程

1. 改代码 → 跑 `build.bat`
2. 上传 `spider.jar` 和 `tv.json` 两个文件到仓库根目录
3. TVBox 里清空配置缓存 → 重新加载

如果 `tv.json` 里的 `spider` 指向 GitHub，地址形如：

```
https://raw.githubusercontent.com/gongchuang1089/CatVodSpider-standalone/main/spider.jar?v=29;md5;<MD5>
```

## 各站点技术要点

### 4K（4kvm.net）

与 `bttwo.life` 同源，都是 nbmovie 模板。详情页带 `id="nb-st"`（服务器时间戳）和 `userlink:'...'`，播放地址由 WASM 生成：

```
GET /video/play?p={dataid}&v={slug}&q={quality}&s={sign}&t={ts}&k={token}
sign  = HMAC-SHA256(key=slug, msg="dataid:ts:slug") 前 32 位小写 hex
token = base64( XOR(userlink, "nbmovie2024secretkey") )
ts    = 当前毫秒时间戳 + (本地时间 - nb-st)
```

- 服务端对 `q=4k` 返回 403（仅客户端），所以统一用 `q=1080` 拉全量 `quality_urls`，再按 `bitrate` 从高到低挑
- 4K / 1080p 档在未登录时是 `isvip=true, locked=true, url="1"`，只有 720p 免费；填 VIP 的 `cookie` 后自动解锁

### 聚合（MacAgg）

10 个源统一走苹果CMS 采集接口：

```
列表  {api}?ac=videolist&t={type_id}&pg={page}
搜索  {api}?ac=videolist&wd={关键词}
详情  {api}?ac=detail&ids={vod_id}
```

`vod_play_url` 格式是 `第1集$地址#第2集$地址`，多线路用 `$$$` 分隔。每个源只取一组（优先直链组），保证线路名就是站名。

### 青花瓷（Qinghuaci）

```
sign = sha1( md5( 参数按 key 排序拼成 k=v& ... + "key=<SIGN_KEY>&t=<ts>" ) )
```

把 `sign` 和 `t` 放在请求头。HTTPS 握手失败会自动降级 HTTP，兼容安卓 6 这类信任库较旧的设备。

### 欧乐影院（Olevod）

官方接口带 `_vv` 参数，是对秒级时间戳做的位混淆：取 `md5(ts)` 的片段，与 `ts` 每个字符的二进制位交叉拼接成 hex3。只取 `vip=false` 的免费直链，因此不进网页播放器，也就没有广告。

## 调试

`test/` 下每个站点有对应的 `Test*.java`，都是带 `main` 的普通类，直接用 JVM 跑：

```bash
javac -encoding UTF-8 -cp "out;android.jar;json-20250517.jar" -d testout test/TestMacAgg.java
java -Dfile.encoding=UTF-8 -cp "out;testout;json-20250517.jar" TestMacAgg
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
| v29 | 修复 `vod_id` 含竖线导致搜索结果被宿主丢弃；聚合分类由 40+ 精简到 12 个；线路顺序按配置固定 |
| v28 | 4K 线路性能优化：gzip、页面/播放/搜索三级缓存、DNS 预热、首分片预取 |
| v27 | 新增聚合站 `agg`（10 个采集源多线路） |
| v26 | 新增 4K 站 `k4k`（4kvm.net，支持 4K 档位） |
| v25 | 初始版本，8 个站点 |

## 免责声明

本项目仅用于技术研究与个人学习，抓取的内容版权归原站点所有。请勿用于商业用途，使用产生的后果由使用者自行承担。

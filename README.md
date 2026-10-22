# 茴香（Foeniculum）

「茴香」（Foeniculum）是一个 ModDevGradle（简称 MDG）的配套 Gradle 插件，插件 id 为 `dev.anvilcraft.foeniculum`，Maven 坐标为 `dev.anvilcraft.foeniculum:foeniculum-gradle-plugin`，以 GNU LGPL 3.0 发布。

它引入一套名为 `slimJarJar` 的机制：让库以「瘦身包 + 独立描述文件」的形式发布，由顶层项目递归读取描述文件，把整棵依赖树压平成一层，再与原生 `jarJar` 的内嵌内容一起写入主 jar 的标准 FML 元数据。整个过程不改变、也不干扰 MDG 原生 `jarJar` 的行为。

## 命名由来

茴香的地上部分有一根粗壮的主茎，主茎上分出大量细密羽状的枝叶。

这个插件把「瘦身构件」——`-slim.jar` 与 `-slim-metadata.json`——比作那些细枝：它们通过 Maven 在库与库之间一层层流转，每一层都只携带自己这一段的依赖描述。而最终只有顶层产品这一根主茎，负责把整棵细枝逐层聚合并压平，收束成主 jar 里唯一一份元数据。

## 要解决的问题

NeoForge 生态里分发库的常用手段是 Jar-in-Jar（缩写 JiJ，即把依赖的 jar 嵌进自己的 jar，运行时由 FML 解包加载）。MDG 原生提供 `jarJar` 配置和同名任务来完成这件事，插件作者只要把依赖写进 `jarJar` 配置，产物里就会带上对应的内嵌包和 FML 元数据。

问题出现在多个库互相依赖、并且都各自 `jarJar` 了下游库的时候。假设 A 依赖 B 与 C，而 B 自己也嵌了 C，那么 A 用原生 `jarJar` 会分别把 B、C 嵌进产物，B 内部又带着一份 C，于是 C 在 A 的产物里出现了两份。jar 体积因此膨胀，运行时还可能出现同一个模块加载到多个版本而引发冲突。

MDG 原生 `jarJar` 针对的是「一个项目内嵌若干直接依赖」这一层语义，它并不知道下游库内部又嵌了什么，因此这个重复问题需要在库的发布方式上解决。

## 解决思路

插件把责任拆成两半：中间库负责「薄」，顶层负责「平」。

中间库安装本插件后，可以额外产出两个构件：一份瘦身包 `-slim.jar`，以及一份独立的描述文件 `-slim-metadata.json`。瘦身包里照旧保留项目自身的原生 `jarJar` 内嵌内容——不装插件时是什么样，装上之后仍然是什么样——但它不再把 `slimJarJar` 的依赖实体打进去，只在描述文件里记下这些依赖的坐标与版本约束。

顶层项目用 `slimJarJar` 声明依赖后，构建时沿着描述文件递归向下走，把整棵树收集起来，对同一模块的所有版本约束取交集并裁决出唯一版本，剔除与原生 `jarJar` 重复的模块，最后把压平后的内嵌包与原生内嵌包一起放进主 jar，并生成唯一一份合并后的 `metadata.json`。

这样无论依赖树嵌套多深，主 jar 的 `META-INF/jarjar/` 下每个模块都只有一份。

## 使用方式

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '<MDG 版本>'
    id 'dev.anvilcraft.foeniculum' version '<茴香版本>'
}

foeniculum {
    // 是否自动把瘦身包与描述文件挂到 maven-publish 的发布上，默认 true
    autoAttachPublishing = true
}

dependencies {
    jarJar 'com.example:legacy-lib:1.0.0'                          // MDG 原生，茴香完全不干预
    slimJarJar 'com.example.lib:core-lib:[2.0.0,3.0.0)'             // 支持版本区间
    slimJarJar 'com.example.lib:util-lib:1.4.2'                    // 也支持固定版本
}
```

`autoAttachPublishing` 只在项目应用了 `maven-publish` 时才有实际作用：开启后，瘦身包以 classifier `slim`、描述文件以 classifier `slim-metadata` 挂到已有的发布上，不需要自己手写 `artifact` 声明；关闭后插件不接触发布配置，由项目自行决定如何发布这两个文件。

## 三个产物

| 产物 | 路径 | classifier / extension | 内容 |
| --- | --- | --- | --- |
| 主 jar | `build/libs/<name>-<version>.jar` | 无 / `jar` | 项目自身代码；`META-INF/jarjar/` 下是原生 `jarJar` 内嵌包 + 压平后的瘦身内嵌包 + 两者合并后的唯一一份 `metadata.json` |
| 瘦身包 | `build/libs/<name>-<version>-slim.jar` | `slim` / `jar` | 项目自身代码；`META-INF/jarjar/` 下只有原生 `jarJar` 的内嵌包与原生 `metadata.json`，与不装本插件时完全一致；若项目没有原生 `jarJar` 依赖，则根本不存在 `META-INF/jarjar/` 目录 |
| 描述文件 | `build/foeniculum/publish/<name>-<version>-slim-metadata.json` | `slim-metadata` / `json` | 只有通过 `slimJarJar` 直接声明的依赖，以及当前项目声明的仓库列表（仅供参考） |

主 jar 是交付给最终用户的那一个，它承担全部聚合职责。瘦身包是中间库发布到仓库里的那一份，它自身保持原样，从而让下游无论嵌套多少层都不会累积重复内容。描述文件不参与打包，只随瘦身包一起发布到 Maven 仓库，供下游构建时读取。

三个产物都在构建过程中一次生成；它们的文件名遵循项目的 `archivesName` 与 `version`，与主 jar 保持一致。

## 描述文件格式

`-slim-metadata.json` 的格式如下：

```json
{
  "schemaVersion": 1,
  "repositories": [
    "https://repo1.maven.org/maven2",
    "https://maven.example.com/releases"
  ],
  "dependencies": [
    {
      "group": "com.example.lib",
      "artifact": "core-lib",
      "versionRange": "[2.0.0,3.0.0)",
      "preferredVersion": "2.4.1",
      "isSlim": true
    }
  ]
}
```

`schemaVersion` 固定为 `1`；读到更高的版本会直接报错并提示升级插件，避免旧插件按旧语义误解新格式。

`repositories` 只做参考，下游解析时会把它与自己的仓库比较，**从不自动注入仓库**。

`dependencies` 只记录直接声明，不展开传递依赖：一棵树的形状由各层描述文件自己拼出来，每一层只对自己声明的东西负责。`isSlim` 表示该依赖是否以瘦身包（`-slim.jar`）形式被内嵌——绝大多数情况为 `true`，只有下游库没能提供瘦身包、回退到普通 jar 时为 `false`。

## 主 jar 中的 FML 元数据

主 jar 里的 `META-INF/jarjar/metadata.json` 是 NeoForge FML 规定的标准 JiJ 元数据，茴香生成的部分与 MDG 原生格式完全一致：

```json
{
  "jars": [
    {
      "identifier": { "group": "com.example", "artifact": "legacy-lib" },
      "version": { "range": "[1.0.0,)", "artifactVersion": "1.0.0" },
      "path": "META-INF/jarjar/legacy-lib-1.0.0.jar",
      "isObfuscated": false
    },
    {
      "identifier": { "group": "com.example.lib", "artifact": "core-lib" },
      "version": { "range": "[2.0.0,3.0.0)", "artifactVersion": "2.0.1" },
      "path": "META-INF/jarjar/core-lib-2.0.1-slim.jar",
      "isObfuscated": false
    }
  ]
}
```

原生命中沿用 MDG 的文件名；茴香内嵌的文件名带 `-slim` 后缀（回退到普通 jar 时则不带），让人一眼看出某个内嵌包是「分支」还是普通 jar。若两个不同 group 的同名同版本模块撞名，解析阶段会再加上 group 前缀区分。

合并规则是确定的：原生 `jarJar` 的条目原样保留且顺序不变，瘦身条目追加在其后，按 `group:artifact` 排序；同一个 `group:artifact` 若同时出现在两侧，保留原生侧并输出一条 Gradle 警告。这条警告是提示而非错误，因为原生声明是项目显式写下的，应当拥有更高优先级。

但「保留原生侧」有一个前提：原生内嵌的版本必须落在瘦身侧对该模块声明的区间内。若原生内嵌的是 `1.0.0`、而下游描述文件要求 `[2.0,3.0)`，运行时 FML 会拿区间去比对实际内嵌的版本并报 Jar-in-Jar 冲突；这种情况在构建期就抛出异常并指出两侧的取值，而不是留下一个「构建成功、启动报错」的产物。此时应把 `jarJar` 的版本调整到该区间内，或去掉其中一侧的声明。

## 递归解析与扁平化

构建时从 `slimJarJar` 的直接声明出发，逐一到项目已声明的 Maven 仓库里取 `group/artifact/version/artifact-version-slim-metadata.json`，再沿着其中的 `dependencies` 递归，直到某一层取不到描述文件为止；取不到描述文件的模块就是这棵树的叶子，会被直接内嵌。

构件优先取 `artifact-version-slim.jar`，不存在时回退取 `artifact-version.jar` 并把 `isSlim` 记为 `false`，同时输出警告。回退是必要的：并非所有下游库都会立刻改用本插件发布，把一个没有瘦身包的普通 jar 当作叶子内嵌，比让整条构建失败更合理。

整棵树最终压平成一层。同一模块出现的所有 `versionRange` 取交集；在出现过的 `preferredVersion` 等候选版本里选满足交集的最高版本；候选都不满足时，回退到仓库 `maven-metadata.xml` 里挑选满足交集的最高版本。交集为空、或没有任何版本满足条件时抛出构建异常，异常信息里列出每个约束的来源链，便于定位是哪一层把版本区间收窄到了无法满足的地步。

判断冲突时不会把迭代过程中的中间状态当成最终结论。一个模块的版本被抬高后，旧版本声称的依赖就不再成立；但抬高之前，旧版本声明的约束可能已经把下游模块的区间压成空集。这种空集只是暂时的，若立刻报错会让一个本来有解的依赖图失败。因此迭代期间遇到空交集会先沿用上一轮的选择让其他模块继续演进，只有整轮决策稳定下来后仍然存在空交集，才判定为不可调和的冲突。

版本区间合并后会被规范化，固定版本改写成只有下界的区间形式，例如固定 `1.2.3` 写作 `[1.2.3,)`，这样描述文件里永远只有一种写法，下游的比较逻辑不必区分固定版本与区间。

去重发生在与原生 `jarJar` 之间：同一 `group:artifact` 在两侧都存在时，瘦身侧条目被丢弃，原生侧原样保留。这条规则同时覆盖了「本项目已用原生 `jarJar` 内嵌某个库，而下游描述文件又把它带进来」的情形，因为这种情况在合并时表现为两侧同名。

下游描述文件里若出现当前项目没有声明的仓库，只会输出一条明确的 Gradle 警告，不会修改 `project.repositories`。构建期的仓库集合由项目自己掌握，插件不替项目做网络访问策略上的决定。

## 明确不支持的情形

只处理 `main` 源集。其他源集的产物不在本插件的处理范围内。

`slimJarJar` 不接受项目依赖（project dependency）。声明了会直接抛出异常并说明原因：这套机制依赖构件经 Maven 仓库流转，项目依赖没有可发布的坐标，也没有描述文件可供下游读取，强行支持只会得到一条无法复现的解析路径。

不接受动态版本（如 `1.+`、`latest.release`）。请改用固定版本或版本区间，解析结果必须能由描述文件本身唯一确定。

## 构建与测试

插件本身是一个普通 Gradle 工程，用 JDK 21 构建：

```shell
./gradlew build
```

`build` 会编译、运行单元测试并执行 `functionalTest`——后者用 Gradle TestKit 在临时工程里驱动插件，验证解析、扁平化与打包行为，其中还包括一个与真实 ModDevGradle 混用的用例。这个用例刻意不配置 `neoForge` 扩展块，因此只要不触碰 Minecraft 相关任务，就不需要下载 Minecraft 与服务端资源。

仓库的 GitHub Actions 工作流在每次推送到任意分支、提交拉取请求以及手动触发时，于 `ubuntu-latest` 上用 Temurin JDK 21 运行 `./gradlew build --stacktrace`。工作流不做任何发布，权限仅为 `contents: read`。

## 许可证

本项目以 GNU Lesser General Public License 3.0 发布。使用、修改与再分发时请遵守该许可证的条款，尤其是关于修改后作品仍需以同一许可证开放源代码的要求。

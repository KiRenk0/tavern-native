# Tavern Native

Tavern Native 1.0.0 使用 Kotlin、Jetpack Compose 和 Material 3 完整原生重写 SillyTavern 的 Android 客户端界面、协议层与运行引擎，全面对标 [SillyTavern 1.19.1](https://github.com/SillyTavern/SillyTavern/releases/tag/1.19.1)（`82b64a45`），并深度融合 Android 平台原生能力（前台后台保活服务、系统分享菜单导入、本地 ONNX 推理与 Keystore 加密存储）。安装包支持 Android 8.0（API 26）及以上。

## 安装与迁移

- **版本升级与签名证书说明**：发布构建（`:app:assembleRelease`）默认生成未签名安装包（`arm64-v8a` 与 `universal`），需使用与已安装版本一致的证书签名后方可覆盖升级并保留本机数据；仓库先前交付的 0.78.0–0.83.0（`-legacy-key-*.apk`）共用 `CN=Tavern Native` 证书（SHA-256 `57ef0b9a...`），而早期 0.48.0 安装包使用独立证书，跨证书安装前请先通过「导出本机账户备份 ZIP」或「设置快照」备份数据再卸载重装。详见 [CHANGELOG.md](CHANGELOG.md)。
- 在原版账户设置下载备份后，在「角色库 → SillyTavern 数据迁移 → 导入原版账户备份 ZIP」直接选择 ZIP。可恢复角色、单聊、群聊、世界书、人设（含 `User Avatars/` 人设头像）、提示词预设、世界书规则、快捷回复、消息图片、锁定背景、图库、自定义媒体资产、扩展中心配置和随包保存的文本资料。也识别压缩包里的 `data/<账户>/` 目录，并在导入时应用 SillyTavern 1.19.1 级别的路径遍历与符号链接安全校验。重复导入按账户和源文件去重。连接凭证在原生应用的提供商设置里配置。 [SillyTavern 账户备份说明](https://docs.sillytavern.app/usage/user-settings/)
- 从同一页面点「导出本机账户备份 ZIP」可备份上述内容，以及标签、作者注释、聊天分支、角色表情、人设头像与便携扩展配置（自动剥离敏感密钥）。原生版可以直接重新导入 ZIP。原版格式的数据位于压缩包内的 `data/tavern-native/`，包含标准角色 PNG、聊天 JSONL 和世界书 JSON。提供商 API Key 和连接密码在迁移后单独配置。
- 如果在 0.3.0 内置引擎预览版使用了本机酒馆，在「角色库」的右上角打开「SillyTavern 数据迁移」，点「导入本机预览版酒馆数据」。应用会复制默认账号的角色卡、单人聊天、群组、群聊、世界书、人设和提示词预设。重复导入会补充新记录。
- 可通过角色 PNG / JSON、聊天 JSONL 和世界书 JSON 在客户端与原版间迁移数据。原生客户端也可从已有 SillyTavern 实例复制角色和单人聊天。

### 设置快照

- 在「设置 → 对话与同步 → 设置快照」保存、重命名、恢复、删除或导出 JSON。恢复前会为现有配置创建本机快照。API Key 和 WebDAV 密码随本机快照加密保存，导出的 JSON 省略这些凭据。恢复同一服务的配置时优先使用本机当前保存的凭据。
- 可导入本应用的快照 JSON，也可从原版 `settings.json` 读取兼容设置，包括外观、自动回复、回复长度与温度、世界书扫描、默认作者注释和全局正则。导入会先加入快照列表，选择恢复后才会应用。 [原版设置快照](https://docs.sillytavern.app/usage/user-settings/#account-management)

### 从 JSON 导入人设和角色

- 用户人设从「设置 → 人设管理 → 导入人设 JSON」导入。支持 SillyTavern 的 `personas_*.json` 备份、单个包含 `name` 和 `description` 的对象、人设数组或 `{"personas":[…]}`。可携带标题、提示词注入位置和深度。重复导入会保留已经保存的人设。原版 JSON 备份中的头像文件需在人设编辑页另选。
- 在「角色库 → 批量导入角色卡」打开系统文件选择器，长按一张卡片后可继续选择其他文件。JSON 支持 SillyTavern V1、V2、V3 角色卡、单个 `{"name":"角色名","description":"角色描述"}`、角色数组或 `{"characters":[…]}`。角色卡中的开场白、世界书及扩展字段会随卡保存。无法读取的文件会在导入结果中提示。
- 同一入口可混选 `.charx` 和 `.byaf` 角色包。CharX 读取角色数据、头像和内嵌图片，支持带 JPEG 封面的布局。BYAF 导入角色、世界书、分场景聊天、备选回复和聊天背景。导入图片可在角色「图库」查看，聊天中的图库也可把图片设为背景。

### 从分享链接导入和更新角色卡

- 在「角色库 → 更多操作 → 从分享链接导入」每行粘贴一个链接或 ID。支持 Chub 角色和世界书、JanitorAI 的公开下载、Pygmalion、AICharacterCards、RisuRealm、Perchance，以及 PNG、JSON、CharX、BYAF 的文件直链。导入结果会显示成功数量和无法读取的链接。各来源的可下载内容由对应网站提供。 [SillyTavern 原版链接导入](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/templates/importCharacters.html)
- 从角色详情的「更多操作 → 替换或更新角色卡」可选择一份文件或填写链接。角色设定随新卡更新，同时沿用已有角色名称、聊天记录、群组归属、图库、本机标签和世界书绑定。带有来源信息的角色可直接打开来源页面。
- 在角色详情点「更多操作 → 复制角色卡」可创建独立副本，带上原角色设定、头像和本机标签。副本有自己的聊天记录。
- 在角色编辑页修改创作者、角色卡版本和创作者备注。角色详情显示版本及完整备注，备注支持 Markdown 和受限 HTML。 [原版角色资料](https://docs.sillytavern.app/usage/core-concepts/characterdesign/#creators-metadata)

### 整理角色和群组

- 角色库和群组库的工具栏可在列表与网格之间切换。在「设置 → 角色库」可选简介、角色卡版本或创作者作为副标题，也能开启按角色设定字段匹配的模糊搜索。防剧透模式默认收起角色描述、开场白及编辑页的主要设定。
- 「设置 → 角色库 → 导入角色卡标签」可选择添加全部、仅添加本机已有标签、跳过标签，或每批导入时询问。这项设置适用于文件和分享链接，角色卡原有的内嵌标签会随卡保留。 [原版角色管理设置](https://docs.sillytavern.app/usage/user-settings/#character-handling)
- 「角色库」可显示头像文件名，或开启导入时保留头像原尺寸。资料库里的头像缩略图仍按显示大小加载。也可分别关闭角色卡系统提示和历史后指令的优先权，让对话提示词预设对应的内容生效。 [原版角色管理设置](https://docs.sillytavern.app/usage/user-settings/#character-handling)
- 角色详情的「更多操作」可以把内嵌世界书提取到世界书库并关联角色。可在角色库设置中开启导入后询问。提取会保留原角色卡内的条目，已有同名世界书会保留。 [原版导入选项](https://docs.sillytavern.app/usage/user-settings/#miscellaneous)

- 点角色卡片或群组卡片右侧的星标收藏。在「角色库」可同时搜索角色和群组，按收藏、类型和标签筛选，也可按名称、最近聊天或聊天数量排序。
- 「标签」中的每项可在「包含」「排除」「不限」之间切换。打开「更多操作 → 管理标签」可调整顺序、颜色和文件夹类型。开放文件夹里的成员也会在主列表显示，隐藏成员的文件夹可点开查看。
- 从角色或群组详情的「更多操作 → 编辑标签」调整本机标签。列表里的「批量打标」支持一次为多个项目添加或移除标签。导入角色卡时可按卡片内嵌标签归类，角色编辑页可以查看和修改原卡内嵌标签。
- 导入原版账户 ZIP 会一并读取标签、角色和群组的归属，以及文件夹设置。独立标签 JSON 可以从「管理标签」导入或导出，无法匹配的角色或群组会在导入结果中提示。 [原版标签管理文档](https://docs.sillytavern.app/usage/core-concepts/tags/)

## 原生模型连接

- 编辑自定义 OpenAI 兼容或 OpenRouter 提供商时，打开「附加请求参数」。支持用 YAML 或 JSON 补充或覆盖请求体、移除指定字段和添加请求头，也可导入原版设置文件的自定义请求字段。聊天请求中的文本值可用 `{{user}}` 和 `{{char}}` 等宏。请求头也用于读取模型列表。YAML 或 JSON 附加参数在本机加密保存，便携设置快照会省略这些字段。 [原版自定义连接参数](https://docs.sillytavern.app/for-contributors/provider-integrations/)
- 「提示词后处理」支持原版的关闭、合并连续同角色消息、半严格、严格和单条用户消息模式，合并、半严格与严格均有保留工具的选项。严格模式必要时插入原版默认用户开场占位文字。可在聊天里的「查看最近请求的提示词」核对处理后的内容。模式可从原版设置 JSON 导入，并随便携设置快照迁移。 [原版提示词后处理说明](https://docs.sillytavern.app/usage/api-connections/openai/#prompt-post-processing)

1. 在「设置」选择「DeepSeek」或「Google Gemini」，填入 API Key 并保存。模板预填服务地址和默认模型。
2. 其他服务用「添加自定义提供商」。可选择 OpenAI 兼容、Claude 原生、OpenRouter、Ollama、KoboldCPP 或 NovelAI 文本补全协议，读取模型列表或手动填写模型 ID。此前通过 Claude 或 OpenRouter 内置模板添加的连接会自动转为可编辑的自定义提供商，并保留已保存的连接和模型。
3. 在单人或群组聊天顶部点模型标签，即可跨提供商切换。选择按对话保存。各提供商的 Key 使用 Android Keystore 加密。

### AI Horde

- 在「设置 → 添加自定义提供商」选择「AI Horde」。填写注册 Key 可使用该账号的排队优先级，留空可匿名提交。读取模型列表后可选择在线模型，也可选 `horde:auto` 由服务从该提供商候选模型中分配。
- 文本补全支持队列位置、预计等待时间、受信任节点筛选以及按节点报告调整输出和上下文额度。点「停止」会撤销已提交的服务器任务。
- 同一连接可在绘图弹窗读取在线图像模型，也可在「图片描述」设置中选为描述来源。绘图和描述任务沿用停止按钮。 [AI Horde REST API](https://aihorde.net/api/)

### NovelAI 文本补全

- 在「设置 → 添加自定义提供商」选择「NovelAI 文本补全」，填写提供商名称和 NovelAI API Key，再点「读取模型列表」。官方地址会读取订阅等级并列出 Erato 和 Kayra。Clio 已从 NovelAI 官方模型列表退役，兼容服务仍可手动配置 `clio-v1`。 [NovelAI 模型文档](https://docs.novelai.net/en/text/models/)
- 在「NovelAI 采样预设」中按模型选择预设、导入或导出 JSON、编辑采样参数和聊天前导文本。预置 SillyTavern 1.19.1 的 24 套预设，兼容 NovelAI v3 预设文件。聊天切换模型时会使用该模型对应的预设。 [SillyTavern 1.19.1 NovelAI 预设](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/default/content/presets/novel)
- 本机使用对应的 Nerdstash、Nerdstash V2 或 Llama 3 词表，计算上下文额度并把停止字符串转为模型词元。流式中断时保留已收到的回复，可以在原消息上继续。 [SillyTavern 1.19.1 分词数据](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/src/tokenizers)

### 词元偏置

- 在 OpenAI 兼容、OpenRouter 或 KoboldCPP / Text Completion 的自定义提供商中打开「词元偏置」。可导入和导出原版偏置预设 JSON，内置 Anti-bond。预设支持调换规则顺序和预览 Token ID。可在提供商内为各模型设置不同预设，或只对指定模型关闭。点「应用词元偏置」后保存提供商。
- 已知 GPT 模型可使用内置词表分词，llama.cpp 和 KoboldCpp 可调用配置的模型服务分词，也可直接输入 Token ID 数组。后端需支持 `logit_bias` 参数。对话补全保留文本的前置空格，文本补全按原版规则在普通文本前加空格，或用大括号原样分词。 [原版对话补全偏置处理](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/src/endpoints/backends/chat-completions.js) [原版文本补全偏置语法](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/logit-bias.js)

### 本地模型采样预设

- 在 KoboldCPP、llama.cpp 或 Ollama 的自定义提供商设置中打开「采样预设」。内置 SillyTavern 1.19.1 的 6 个 Text Generation 和 6 个 Kobold 预设，可导入这两类 JSON、编辑兼容字段和导出 JSON。可按提供商或模型 ID 保存，聊天顶部切换模型后即采用对应预设。 [原版采样设置](https://docs.sillytavern.app/usage/common-settings/)
- 可设置温度、Top P、Top K、Min P、重复惩罚、随机种子和 Token 额度。动态温度、DRY、采样器顺序和结构化输出等高级字段依后端支持发送。连接配置及全局生成设置中填写的温度、Top P 或最大回复长度优先。上下文上限参与世界书预算，KoboldCPP 和 Ollama 也会在请求中收到该上限。

### 文本补全指令模板

- 自定义提供商选择 KoboldCPP / Text Completion，或为 Ollama 开启 Text Completion，即可打开「文本补全指令模板」。内置 SillyTavern 1.19.1 的 38 个模板，包括 ChatML、Llama 3 Instruct、Gemma 和 Alpaca。可以修改模板，或用原版的 Instruct JSON 导入和导出。选好后点「应用此模板」，再保存提供商。 [原版指令模板](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/default/content/presets/instruct)
- 指令模板为系统消息、用户消息、角色消息和示例分别组装前后缀。续写会从已收到的角色正文接着生成，代写会切换到用户前缀。对话中的「查看最近请求的提示词」可查看实际发送的文本和停止序列。 [原版 Instruct Mode 文档](https://docs.sillytavern.app/usage/core-concepts/instructmode/)

### 按模型切换模板

- 在文本补全提供商内打开「按模型切换模板」，按模型 ID、正则（例如 `/qwen/i`）或后端模板哈希为模型绑定上下文和指令模板。聊天顶部切换模型后，生成会依次匹配模型 ID、正则和模板哈希。多条正则命中时，按指令模板名称排序，采用第一条。没配置匹配规则的模型会沿用提供商模板或自动识别的模板。
- KoboldCPP 使用服务根地址或 `/api/v1`。llama.cpp 使用带 `/v1` 的服务地址。可选用服务端 `/props` 识别格式和读取上下文长度，供请求及世界书预算计算。应用包含原版的 19 个哈希映射，以及原版对 ChatML、Moonshot AI 和 OpenAI Harmony 的识别规则。读取模型属性后，可以为共享同一模板哈希的模型保存一份模板绑定。 [原版后端模板识别](https://docs.sillytavern.app/usage/core-concepts/advancedformatting/#backend-defined-templates)

### 文本补全上下文模板

- 从同一提供商设置打开「文本补全上下文模板」，可以使用 SillyTavern 1.19.1 的 34 个预设，编辑故事正文，或导入和导出原版 Context JSON。有对应预设时，可同时应用同名指令模板。 [原版预设](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/default/content/presets/context)
- 故事正文使用 Handlebars，可引用角色卡、人设、世界书、示例和扩展提示。在顶部显示，或按深度及消息角色插入聊天历史。可以分别设置例子与聊天起始分隔符、停止序列、纯文本补全时的角色名，以及是否仅生成一行或裁去正常结束时未写完的句子。角色卡与聊天记录的内容可通过「查看最近请求的提示词」核对。 [原版上下文模板文档](https://docs.sillytavern.app/usage/prompts/context-template/)

### 高级格式化

- 在「设置 → 高级格式化 → 文本补全系统提示」可选择原版的 13 个系统提示预设，编辑系统提示和历史后指令，也可保存多个自定义预设并导入或导出原版 JSON。角色卡提示有独立的优先开关，可以用 `{{original}}` 引用所选预设。文本补全的上下文模板通过 `{{system}}` 引用系统提示。
- 在「高级格式化 → 自定义停止序列」输入 JSON 数组，可在聊天补全和文本补全中使用，例如 `["<END>", "\n{{user}}:"]`。启用宏展开后会按当前对话替换名称。回复中出现停止序列时，应用会裁去序列及后续内容，包括序列跨越多个流式分片的情况。后端拒绝停止参数时，会保留手机本地的检测。 [原版高级格式化](https://docs.sillytavern.app/usage/core-concepts/advancedformatting/)
- 「回复开头预填」支持角色、人设等宏，用于新回复、重新生成和生成备选回复。可以隐藏预填开头，后续请求仍会保留它。DeepSeek 内置连接使用官方 Beta 前缀接口。聊天模型明确拒绝助手预填时，应用会改用开头指令重试。 [DeepSeek 前缀补全文档](https://api-docs.deepseek.com/guides/chat_prefix_completion/)
- 「推理格式」内置 Blank、DeepSeek、Gemma 4、OpenAI Harmony 和 Think XML，可编辑或导入、导出原版 JSON。启用自动提取后，流式分片中的推理与正文分别显示并保存。可指定推理面板是否自动展开，以及把最近多少条推理带入后续请求。生成停在推理阶段时，已收到的推理可以在消息中查看。 [原版推理格式文档](https://docs.sillytavern.app/usage/prompts/reasoning/)

## 提示词管理器

- 在「设置 → 提示词预设」新建和编辑预设，或导入 SillyTavern 的 Chat Completion 预设和 Prompt Manager 导出 JSON。可调整提示词顺序、启用状态、角色、注入深度和触发类型，也可锁定主提示词或历史后指令。预设 JSON 可单独导出。
- 对话菜单可指定预设。自动选择先匹配同名角色或群组，再使用全局默认。没有匹配时使用内置基础提示词。在同一对话发起请求后，可查看并复制最近一次组装的提示词。
- 在预设的「生成参数和模板」设置发言者名称、空消息替代文字、续写连接方式、流式回复和聊天补全上下文上限。名称可省略、按原版默认策略插入、作为 API 的 `name` 字段发送或写入正文。Gemini 原生等没有消息名称字段的接口会把名称放入正文。设置随原版格式的预设 JSON 导入和导出。 [原版 Prompt Manager](https://docs.sillytavern.app/usage/prompts/prompt-manager/)
- 启用「续写时用助手消息预填充」后，DeepSeek 使用 Beta 接口接续上一条助手消息。后端拒绝助手预填充时，应用会保留上一条消息并发送预设里的续写提示。若选了空格、换行或空一行，生成中的预览和保存后的回复使用同一种连接方式。 [DeepSeek 前缀补全](https://api-docs.deepseek.com/guides/chat_prefix_completion/)
- 在预设的「生成参数和模板」打开「启用联网搜索」。DeepSeek Flash 可搜索并读取网址，自定义 Claude 原生或支持工具调用的 OpenRouter 模型使用对应的服务端搜索工具。回复的「联网来源」可打开服务返回的引用。来源随各条备选回复保存，可通过 JSONL 或聊天 ZIP 迁移。使用同一模型及连接时，DeepSeek 和 Claude 可在后续请求中恢复兼容的搜索上下文。 [DeepSeek Web Tools](https://api-docs.deepseek.com/guides/web_tools/) · [Claude Web Search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool) · [OpenRouter Web Search](https://openrouter.ai/docs/guides/features/server-tools/web-search)
- 同一开关支持 Gemini 4 Hydrogen 等兼容文本模型。内置 Google 模板自动通过 Gemini 原生 API 发出搜索请求，支持流式和非流式接口。模型使用搜索时，当前聊天会同时显示回复、Google 搜索建议和来源。联网回复随聊天页面关闭而清除。 [Gemini 搜索](https://ai.google.dev/gemini-api/docs/google-search) · [Gemini API 使用条款](https://aistudio.google.com/docs/terms#grounding-with-google-search)
- 生成时按当前模型的上下文与预留回复 Token 选取聊天历史。空间不足时省略较早的消息，空间足够时可纳入超过 80 条。「高级格式化 → 对话示例」可设置有剩余额度时保留、优先保留或不发送。原版设置快照中的示例偏好也会迁移。 [原版上下文设置](https://docs.sillytavern.app/usage/common-settings/#context-settings)
- 聊天记录标出上次请求从哪条历史消息开始。最近请求的提示词页面显示输入 Token、预留回复额度、计数方式和省略的旧消息数量。图片 Token 和缺少本机分词表的模型使用估算。
- 预设可保存温度、Top P、输出长度、频率惩罚、存在惩罚和随机种子。内置提供商按各自支持的参数发送。设置页填写的全局温度或输出长度会覆盖预设对应值。连接地址和 API Key 在提供商设置中独立管理。

### 提示词宏

- 角色卡、预设、作者注释和世界书可使用原版的角色与人设字段、群组成员、当前模型、可见聊天历史和摘要宏。新对话的角色开场白也会展开宏。
- 提示词和 STscript 可用 `{{setvar::score::0}}`、`{{incvar::score}}` 和 `{{deletevar::score}}` 保存、增加或删除变量。局部变量随对话保存，全局变量可在不同聊天中读取。简写支持赋值、加减、比较和默认值，例如 `{{.score += 2}}` 和 `{{.name ??= 旅者}}`。列表或对象变量可用 `setvarkey` 和 `getvarkey` 按索引或键读取和更新。
- 模型请求完成组装时保存本轮变量改动。流式回退和函数工具追加请求复用已组装的内容。世界书扫描和复用历史消息时只读取变量。
- 支持嵌套参数与 `{{if .flag}}…{{else}}…{{/if}}` 条件、局部和全局变量读取、注释、转义、`{{random::甲::乙}}`、`{{pick::甲::乙}}`、`{{roll::2d6+1}}` 和常用时间宏。`pick` 在同一对话保持稳定，运行 `/reroll-pick` 可重新抽取。可用 `/? macros` 查看常用宏。 [原版宏文档](https://docs.sillytavern.app/usage/core-concepts/macros/)

## 正则扩展

- 在「设置 → 正则扩展」新建、排序、测试和导入导出规则。支持原版的全局、角色卡和提示词预设三种范围。角色卡和预设内的规则可在各自范围中启用。
- 规则可处理用户消息、模型回复、模型推理和即将注入的世界书条目。仅修改显示和仅修改发给模型的内容使用临时副本。对话深度和编辑时触发可按规则调整。
- 支持常见的斜杠标志、数字和命名捕获组、`{{match}}`、匹配中的删减文本，以及带转义的角色名和人设名宏。世界书关键词也可使用斜杠正则。
- 模型提供的推理可在消息中展开、编辑或清除。需要在下一次生成中使用推理时，可在「设置 → 生成参数」开启随历史附带推理。

## 原生对话功能

- 在「首页」直接和助手开始聊天。可在角色详情的「更多操作」将角色设为首页助手。首页的最近聊天涵盖单聊和群聊，可置顶常用记录。在「设置 → 对话」可调整显示条数，或让应用启动时打开上次保存的聊天。
- 从首页进入「临时聊天」可使用中性助手。临时聊天及附件保存在当前会话和缓存中，关闭后从应用内清除。需要保留时，从聊天顶部的「更多操作」导出 JSONL 或带附件的 ZIP，再从首页「载入临时聊天」恢复。
- 在对话设置中续写最后一条角色回复，或代写一条可以编辑再发送的输入框草稿。最后一条角色回复的右箭头可以切换到下一条备选回复，或在末尾生成新的备选回复。
- 点消息底部的备选计数，或从消息菜单打开「查看备选回复」，可预览、跳转和删除备选。至少保留一条回复。
- 在「设置 → 对话 → 回复自动化」可设置自动续写的 Token 目标，或按最低字数和屏蔽词出现次数自动生成备选。每条角色回复的额外请求数可限制在 1–5 次。每段收到的正文会先保存。过滤结束的回复以及输入框中已有草稿的聊天会暂停自动追加请求。 [原版自动续写和备选设置](https://docs.sillytavern.app/usage/user-settings/#chatmessage-handling)
- 在消息菜单从指定消息创建分支，或命名并保存检查点。在分支或检查点的对话设置中可返回父对话。在消息菜单也可复制、移动消息，在对话设置中可查找或批量删除消息。
- 可在聊天外观设置中开启「展开消息操作」，在消息底部横向滑动查看快捷按钮。编辑消息时可直接上移、下移、删除，或把正在编辑的内容复制为下一条消息。移动会保留编辑中的文字，复制会保留原消息。 [原版消息编辑](https://docs.sillytavern.app/usage/chatting/#edit-message-content)
- 在「设置 → 外观」可选择平铺、气泡或文档小说排版，调整聊天宽度，选择圆形、长方形、正方形或圆角方形头像，也可隐藏聊天头像。可独立开启消息编号或每条消息的备选计数。原版账户备份和设置快照可迁移这些选项。在聊天输入 `/flat`、`/bubble` 或 `/story` 可直接切换排版。 [原版界面自定义](https://docs.sillytavern.app/usage/core-concepts/uicustomization/)
- 「外观」还能开启轻点正文编辑或紧凑输入栏，设置六种应用内提示位置，或让「回到底部」直接跳转。紧凑输入栏的「＋」菜单保留附件、绘图和群聊自动发言操作。
- 在「设置 → 外观 → 聊天主题」可以保存和切换外观预设、更新或删除已有主题，以及导入或导出原版 SillyTavern 主题 JSON。同名导入会生成独立副本。可调整正文、斜体、下划线、引号、聊天背景、界面背景、边框、消息气泡及文字阴影的颜色和透明度。主题会随设置快照或账户备份迁移。原版自定义 CSS 保存在导出的主题 JSON 中，Android 界面使用原生配色。 [原版主题管理](https://docs.sillytavern.app/usage/core-concepts/uicustomization/#theme-management)
- 「聊天主题」内置 SillyTavern 1.19.1 的 Azure、Cappuccino、Celestial Macaron、Dark Lite 和 Dark V 1.0，应用后可继续编辑和导出。 [原版主题文件](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/default/content/themes)
- 在「外观」开启收藏快捷栏，可以从聊天页打开收藏角色或群组的最近对话。模型标记显示该条备选回复实际使用过的模型。开启群聊发言队列后，可查看本轮发言者及待发言顺序。开启横滑后，在最新回复上右划返回上一条备选，左划切换到下一条或生成新备选。 [原版用户设置](https://docs.sillytavern.app/usage/user-settings/#chatmessage-handling)
- 在「设置 → 对话与同步」可将回车设为换行、仅实体键盘回车发送，或启用输入法的发送动作。可分别开启输入栏的「续写」和「代写」按钮。启用空输入续写后，当单聊的最后一条是可续写的角色回复，且输入及附件均为空，发送键会续写该回复。关闭自动滚动后，新消息会保留当前阅读位置，仍可手动跳到底部。 [原版输入与回复控制](https://docs.sillytavern.app/usage/user-settings/#input--response-controls)
- 「对话与同步」也可开启消息编辑自动保存、调整删除消息及备选回复的确认方式，以及显示或隐藏备选箭头。开启 Markdown 修复后，会清理强调标记附近的多余空格，临时在显示中补齐部分未闭合的标记。回复完成提示音支持试听及仅在后台播放。 [原版消息管理](https://docs.sillytavern.app/usage/user-settings/#auto-management) · [原版提示音](https://docs.sillytavern.app/usage/core-concepts/uicustomization/#message-sound)
- 可设置先显示最近多少条消息，并从聊天列表顶部加载较早的消息。搜索和归档跳转会自动展开目标位置，模型仍按原来的上下文预算读取聊天记录。
- 流式回复可设 5–100 FPS 的刷新上限、逐字显示速度和推理即时显示，也能开启新增正文和推理文字的淡入。动画只作用于生成时的预览，完成后立即显示完整回复。
- 开启「重新打开聊天时恢复草稿」可在单聊或群聊保留未发送的文字。草稿按聊天在本机保存，临时聊天结束后会随会话清除。 [原版用户设置](https://docs.sillytavern.app/usage/user-settings/#miscellaneous)
- 在「对话与同步」开启「群聊回复允许包含其他成员的台词」后，应用会允许一条回复中出现以其他群成员名字开头的台词。默认按当前发言角色截断这类续写。 [原版群聊回复选项](https://docs.sillytavern.app/usage/user-settings/#message-formatting--display)
- 对话菜单的「重新载入当前聊天」会重读本机记录并保留输入栏草稿。「对话与同步」也可压缩新回复和文本补全提示词中的连续换行、让单行普通设置输入框获得焦点时选中现有文字，并启用 Markdown 键盘快捷键。支持 `Ctrl+B`、`Ctrl+I`、`Ctrl+U`、`Ctrl+K`，以及 `Ctrl+Shift` 加反引号。 [原版输入选项](https://docs.sillytavern.app/usage/user-settings/#miscellaneous)
- 首页顶部的搜索按钮可按名称或正文搜索全部已保存的单聊和群聊。角色、群组和聊天页的「更多操作 → 搜索历史聊天」会限定当前角色或群组。多个关键词可分别出现在同一聊天的不同消息中，点结果可跳到对应消息。备选回复列表也可直接从指定备选创建分支，原对话仍保持当前选中的回复。 [原版聊天文件管理](https://docs.sillytavern.app/usage/core-concepts/chatfilemanagement/)
- 在目标角色或群组的详情页，点「导入聊天记录」，可直接从本机的群组或角色选择一段聊天，也可多选 JSONL、JSON 或带附件的 ZIP。导入会创建独立的聊天，同名副本会加序号。聊天文件里已有的消息顺序、发言者名称、时间和备选回复会按格式迁移。
- 文件导入识别原版 JSONL、Chub 的嵌套消息和备选，以及 SillyTavern 支持的 Agnaistic、Text Generation WebUI、CAI Tools、KoboldAI Lite 和 RisuAI JSON。一个 CAI Tools 文件里的多段对话会分别保存。批量导入会报告失败的文件数量和首个错误。 [SillyTavern 1.19.1 导入实现](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/src/endpoints/chats.js)
- 在聊天页的顶部菜单可以导出 JSONL、纯文本或带附件的 ZIP。角色聊天可「复制到已有群组」，也可用「复制为群聊」新建群组。群聊可「复制到角色」。群组会匹配能识别的成员。
- 从当前聊天的「对话设置」或角色／群组的「更多操作」打开「管理聊天文件」，可按标题查找并切换聊天。可以直接重命名同一角色或群组的历史记录，或导出 JSONL、文本及带附件的 ZIP。在当前对话也可直接新建或关闭聊天。 [原版聊天文件管理](https://docs.sillytavern.app/usage/core-concepts/chatfilemanagement/)
- 对已有检查点的消息，打开消息菜单的「更新检查点」，可用当前聊天截至该消息的内容更新原检查点。检查点保留原来的文件身份和链接。 [原版聊天操作](https://docs.sillytavern.app/usage/chatting/#content-management)
- 从角色详情、群组详情或聊天页的「更多操作 → 聊天备份」预览、恢复或永久删除备份，并可关闭自动备份。默认会在已保存的聊天变更或删除前保留快照；也可点「备份当前聊天」手动保存。恢复会在当前角色或群组下创建副本。每段聊天最多保留 50 个版本，本机最多保留 1,000 个版本及约 128 MiB 的聊天快照。原有附件通过本机文件引用恢复。删除整个角色或群组会一并删除其备份。 [原版聊天备份](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/chat-backups.js)
- 在「群组」新建群组、排序和静音成员。发言顺序支持自然触发、按成员顺序、随机轮转和手动指定。上下文可使用当前发言者设定、合并启用成员设定或合并全部成员设定。对话顶部的发言按钮可指定角色回复。
- 在群组编辑页选择图片，拖动或缩放裁剪群组头像，也可恢复由最多四张成员头像组成的拼图。头像会显示在群组列表、群组详情、聊天顶部和首页最近聊天，可随账户 ZIP 导入和导出。 [原版群组头像](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/group-chats.js)
- 新建单聊后可直接用首条消息下的箭头循环切换角色卡里的备选开场白。开场白及选中项随聊天记录保存。新建群聊时，成员按群组顺序显示角色卡里的开场白，有多个时随机选择。 [原版角色开场白](https://docs.sillytavern.app/usage/core-concepts/characterdesign/#alternate-greetings)
- 角色编辑页可调整自然群聊中的发言积极度。群聊顶部的「群聊成员」可编辑任一成员的角色设定，保存或返回后继续当前聊天。也可从这里指定成员立即发言。 [原版群聊设置](https://docs.sillytavern.app/usage/core-concepts/groupchats/)
- 在群组编辑页设置自动发言间隔，在群聊顶部开启自动发言。输入新消息、切换对话或离开应用会停止安排后续轮次。「停止生成」也会取消当前回复。重新生成可替换上一个群聊轮次的回复。
- 在「设置」的「人设管理」创建多个人设，设置头像、默认身份、描述的注入方式及关联世界书。在对话菜单中可以临时切换人设，也可以绑定到当前对话、角色或群组。可从已有角色卡创建人设。
- 在对话菜单中编辑作者注释，或在「场景与提示覆盖」为当前聊天设置场景、对话示例和系统提示。作者注释支持每隔若干条用户消息注入、设定深度和消息角色，也可以放在场景前后。在设置页可配置新对话的默认注释，在角色编辑页可配置角色专属注释和角色深度提示。
- 在「世界书」管理、搜索和编辑条目。世界书支持全局启用，也可绑定到角色、人设或单个对话。生成时会根据扫描深度、主／次关键词、匹配逻辑、优先级、触发概率和注入位置组装请求。
- 在世界书首页打开「全局扫描设置」配置递归扫描与最少激活数。在条目编辑页打开「高级规则」设置互斥分组、权重、开场延迟、保持激活、冷却和参与扫描的人设或角色设定。选用「锚点」注入时，可以在提示词中用 `{{outlet::名称}}` 调用条目。
- 在「高级规则 → 激活范围」按角色、资料库标签及生成类型筛选条目，可排除指定角色或带指定标签的角色。群聊按当轮发言者匹配。过滤同时适用于常驻、关键词和向量激活。原版账户 ZIP 可迁移角色和标签关联，导出的账户 ZIP 会按备份中的角色文件名记录过滤条件。 [原版世界书条目设置](https://docs.sillytavern.app/usage/core-concepts/worldinfo/#character-filter)
- 在角色编辑页选择一个主世界书和多本附加世界书。导出角色卡时会嵌入主世界书，附加世界书的关联可随账户 ZIP 迁移。在「全局扫描设置」可指定默认扫描深度、是否匹配发言者名称，以及角色书与全局书竞争额度时的激活顺序。单本书和单条条目仍可指定自己的深度。 [原版世界书设置](https://docs.sillytavern.app/usage/core-concepts/worldinfo/)
- 世界书详情的「更多世界书操作」可复制整本书，或用首个关键词补齐所有空白条目标题。每条的「条目操作」可在本书复制，也可复制或移动到另一部世界书。开启「自定义顺序」后可上移或下移条目，也可按标题、注入优先级、激活方式、正文长度或编号排序。自定义显示顺序随世界书文件迁移。 [原版世界书编辑器](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/world-info.js)
- 「导入世界书」识别 SillyTavern 世界书、角色卡内嵌世界书、NovelAI 世界书 JSON 或含 `naidata` 的 PNG，以及 Agnai Memory Book 和 RisuAI 世界书 JSON。 [原版导入格式](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/world-info.js)
- 含 `\x01` 或 `\u0001` 的关键词正则可匹配单条消息的边界。配合「匹配消息时包含发言者名称」，可用 `/\x01{{user}}:[^\x01]*?hello/i` 查找用户说过的 `hello`。 [原版逐消息正则](https://docs.sillytavern.app/usage/core-concepts/worldinfo/#advanced-regex-per-message-matching)
- STscript 支持 `/world state=toggle 书名`、`/getglobalbooks`、`/getchatbook`、`/getpersonabook` 和 `/getcharbook type=all 角色名`。可用 `create=true` 创建并绑定缺失的世界书。条目操作支持 `/findentry file=书名`、`/getentryfield`、`/setentryfield` 和 `/createentry`。 [原版世界书脚本命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/world-info.js)
- 在「世界书 → 全局扫描设置」可设置上下文百分比、固定 Token 上限和溢出提示。计算会采用当前聊天所选模型的上下文规格，并预留已配置的输出额度。读取模型列表可更新服务返回的规格，提供商的模型选项可手动设定更低的上下文上限。未知规格默认按 8,192 Token 计算。
- 「查看最近请求的提示词」会显示本次世界书条目用量与计数方式。已匹配到词表的 DeepSeek V4.1 和 GPT 模型会在本机分词。未匹配词表的模型使用区分语言的本机估算。高级规则中的「忽略世界书注入额度」适用于需要独立注入的条目。

### 消息详情和聊天统计

- 在「设置」开启「请求 Token 概率」并保存后，可在支持的 DeepSeek、OpenAI 兼容、OpenRouter、KoboldCpp 或 llama.cpp `/v1/completions` 连接生成新回复。消息菜单的「Token 概率」显示实际生成的 Token、服务返回的候选和概率。每个备选回复可保存前 1,024 个带概率的 Token，可随聊天 JSONL 迁移。使用 DeepSeek、KoboldCpp 或 llama.cpp 时，可从最后一条回复选择备用 Token 并创建新的续写备选。 [原版 Token 概率面板](https://docs.sillytavern.app/usage/chatting/#token-probabilities-panel) · [DeepSeek 聊天补全文档](https://api-docs.deepseek.com/api/create-chat-completion/)
- 在「设置 → 外观」可显示消息时间、回复生成用时和正文 Token 计数。「消息操作 → 消息详情」显示模型、收到第一段正文的耗时，以及服务返回的输入、输出和推理 Token。无分词表时正文计数使用本机估算。每条备选回复保存独立的生成信息，可随聊天 JSONL 或账户 ZIP 迁移。
- 在「设置 → 对话与同步 → 聊天统计」查看全部已保存的聊天。对话设置和角色、群组详情也有对应入口，可查看消息、正文词数、备选数量和已记录的生成时间。

### Token 计数器

- 在单聊或群聊「对话设置 → Token 计数器」查看输入稿的 Token 数，也可以输入任意文本或统计当前聊天正文。支持选择 DeepSeek V4.1 和 GPT 词表、复制 Token ID，并用颜色查看前 512 个片段。Gemini、KoboldCpp 和 llama.cpp 可手动请求服务端统计当前输入。Gemini 的统计接口只返回数量。 [原版 Token Counter](https://docs.sillytavern.app/extensions/) · [Gemini 统计接口](https://ai.google.dev/api/tokens)
- 在提供商的「本机分词器」选定词表后，聊天预算、世界书预算、回复长度统计和 STscript 同时使用该词表。聊天顶部切换模型时，自动模式会重新匹配词表。运行 `/count` 可统计当前聊天的非系统消息正文并把数字传到后续命令。`/trimtokens limit=20 direction=end …` 使用当前分词器截取正文。 [DeepSeek V4.1 词表](https://huggingface.co/deepseek-ai/DeepSeek-V4.1-Flash/blob/314113f8a7dadb388215396ba5fbee2c610a04d6/tokenizer.json)

### 同世界书角色记忆

- 打开「世界书」，进入需要联通的书并开启「联通角色记忆」。在各个单聊或群聊的「对话设置 → 对话世界书」绑定这本书。聊天未指定世界书时，使用发言角色在角色编辑页绑定的世界书。全局启用的世界书继续参与条目激活。
- 生成时先筛选同一本世界书下的聊天，再按角色卡身份检索。使用同一张角色卡的单聊和群聊可以互相召回经历，不同群聊之间也可以召回。同名角色卡各自独立。应用最多读取最近的 24 个相关单聊和 24 个相关群聊，从入选聊天的历史中检索当前话题，并可摘录长回复中包含线索的段落。每轮最多选取 6 段随回复请求发送给当前模型。在「查看最近请求的提示词」中可核对实际召回片段。
- 召回直接读取已保存的文字消息，支持已有聊天记录。世界书改名会保留聊天的绑定，包括通过书名绑定的旧记录。关闭开关即可停止共享。选择带附件的聊天 ZIP 或世界书 JSON 导出仍按各自的格式保存内容。

### 视觉小说模式

- 在「设置 → 外观」或聊天顶部的「对话设置」开启视觉小说模式。单聊显示角色立绘或头像。群聊按成员顺序排布角色，突出显示正在发言的角色。群组编辑页可选择隐藏已静音的角色。
- 从「对话设置 → 从文件设置对话背景」选择图片，也可沿用图库或生图设定的背景。开启自动表情后，场景会随角色回复更新。尚未启用表情切换时，场景使用已导入的中性立绘或角色头像。
- 窄屏将场景放在聊天记录上方，宽屏可并排显示。可用聊天顶部的「展开聊天记录」临时收起场景。输入、搜索和批量选择时也会为聊天记录腾出空间。

### 背景库

- 在「设置 → 外观 → 背景库」批量导入图片、搜索、预览和重命名，也可从角色或群组图库添加。设为默认背景后，未锁定的单聊和群聊会使用它。普通聊天和视觉小说模式都可选择裁剪、完整显示、拉伸或居中。
- 在「对话设置 → 背景库」可锁定专属背景，解除锁定后会跟随默认背景。支持 `/bg 背景名称`、`/lockbg` 和 `/unlockbg`。账户备份 ZIP 可迁移背景库、默认项和对话锁定。 [原版背景操作](https://docs.sillytavern.app/usage/st-script/#ui-styling)

### 角色表情

- 从角色详情或对话菜单打开「角色表情」，可多选图片或导入平铺的表情 ZIP。留空标签时按文件名识别，例如 `joy.png` 和 `joy-2.png` 对应同一个表情。CharX 的内嵌表情以及由旧版保存在图库里的表情会出现在列表中。
- 在对话中选「手动」或「用当前聊天模型自动切换」。自动模式按每位角色最近一条已完成的可见回复分类，向当前聊天模型发送该段回复和标签。可修改分类提示，并设置默认图片、内置表情符号或隐藏。单聊和群聊分别保存自己的设置。
- 选「用本地模型自动切换」，下载或导入约 67.6 MB 的量化 GoEmotions 模型，然后保存设置。模型安装后，应用使用 ONNX Runtime 在手机上离线分类，支持 28 个原版标签。模型主要针对英文。 [模型来源与许可](https://huggingface.co/Cohee/distilbert-base-uncased-go-emotions-onnx/tree/d22488bc83be87678f12eee8a3f65a65de94ef85)
- 点某张表情的「显示」可立即切换。快捷回复和 STscript 可用 `/expression-set joy`、`/expression-set name="角色名" joy` 和 `/expression-list`。

## STscript 和快捷回复

- 在聊天输入框运行斜杠命令。支持用 `|` 串联命令、`{{pipe}}`、`{: ... :}` 闭包、局部和全局变量，以及 `/if`、`/times`、`/while` 和 `/run`。可用 `/help` 查看已接入的核心命令。
- `/send`、`/sendas`、`/sys` 可将文本加入聊天。`/trigger` 生成正式回复，`/gen` 将模型输出交给下一个命令。`/input` 和 `/buttons` 打开原生对话框。`/regex name=规则名` 调用已启用的规则。脚本可用输入框旁的停止按钮中止。
- `/replace`（`/re`）支持按原文或 `mode=regex` 替换，并识别 `$&`、`$1` 等替换标记。`/test` 返回是否匹配，`/match` 返回完整匹配与捕获组，带 `g` 时返回多组 JSON 数组。`/array-wrap`（`/list-wrap`）将值包装成数组，可用 `stringify=false` 保留 JSON 基本类型。`/array-unwrap`（`/list-unwrap`）取数组第一项。这五个命令的结果均可传给下一段脚本。 [原版 STscript 命令定义](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)
- `/getchatname` 读取当前聊天的名称，`/renamechat 新名称` 保存名称，`/chat-reload` 重读已保存的聊天并保留草稿，`/closechat` 关闭聊天并返回主界面。`/chat-jump 0` 跳到第一条消息并高亮，较早的消息会自动展开。也可用 `/chat-scrollto` 或 `/floor-teleport`。 [原版聊天脚本命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)
- 在「设置 → 快捷回复」新建、排序、导入和导出 SillyTavern 回复集。启用全局回复集，或在对话菜单额外启用该对话的回复集。可按启动、打开或新建聊天、发送用户消息、生成前、群成员发言和收到模型回复触发。快捷回复与世界书条目使用相同的自动化 ID 时，条目激活会运行回复脚本。可在每条快捷回复中控制是否允许嵌套触发。

- `/gen` 使用当前聊天上下文并把回复传给下一条命令，支持临时输出长度、停止序列、角色名称和完整句裁剪。`/genraw` 只组装命令提供的文本、系统提示和预填内容，并使用当前模型与采样设置。文本补全可用 `instruct=off` 跳过指令模板。`/sysgen` 生成一条旁白，可指定名称、插入位置、紧凑显示与返回方式。 [原版 STscript 生成命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)
- 在单聊运行 `/ask name="角色名" 问题` 可以请角色库中的另一张角色卡临时发言。聊天归属仍为原角色，来客回复以自己的身份保存。续写或重新生成来客的回复时会继续使用来客设定。默认把回复正文传入管道，也支持 `return=object` 取得完整消息对象。 [原版角色临时发言命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)
- 在群聊中，`/member-get` 和 `/member-count` 可查询成员与顺序，`/member-add`、`/member-remove`、`/member-enable`、`/member-disable`、`/member-up`、`/member-down` 可保存成员变更。`/member-peek` 在当前聊天上预览角色卡。成员参数可使用从 0 开始的索引、名称或头像键。`/trigger await=true 角色名` 等待指定成员完成回复，省略 `await` 可排队后继续执行后面的命令。 [原版群组脚本命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)

- `/char-find` 可按名称、头像键及多个资料库标签查找角色，`/char-get` 可读取字段或返回角色对象。`/char-create`、`/char-update` 和 `/char-duplicate` 返回可传给后续命令的头像键。支持从文件选择并裁剪头像、用现有图片设置头像，以及更新开场白、世界书和深度提示。`/char-delete` 默认保留聊天和备份，可从「群组 → 角色名 · 已归档」打开聊天。设置 `deleteChats=true` 会同时删除关联聊天和备份。`silent=true` 可跳过删除确认。 [原版角色库脚本命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/slash-commands.js)

### 操作对话消息

- `/branch-create 6` 从编号 6 的消息建立并打开分支，`/checkpoint-create mesId=6 路口` 为同一位置保存检查点。也可用 `/checkpoint-get`、`/checkpoint-go`、`/checkpoint-parent`、`/checkpoint-exit` 和 `/checkpoint-list links=true` 读取或打开相关聊天。 [原版检查点命令](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/bookmarks.js)
- `/messages names=on hidden=off role=assistant 2-5` 读取第 2 到第 5 条消息中的可见角色回复。编号从 0 开始，范围包含两端。
- `/hide 2-5` 将消息从模型上下文中隐藏，`/unhide 3` 恢复。消息仍显示在对话中，也能从单条消息的操作菜单切换。隐藏内容会从模型历史、世界书扫描、摘要和聊天召回中排除。
- `/message-name at=-1 新名字` 修改最后一条消息的发言者，`/message-role at=-1 system` 将它设为旁白。省略新值可读取当前名称或角色。
- `/addswipe switch=true 备选回复` 添加并选中备选，`/swipe direction=left` 选上一条，`/delswipe 2` 删除第二个备选。备选的删除编号从 1 开始。
- `/cut 2-4` 删除指定范围并把原文送入管道，`/del 2` 删除最后两条，`/delname 角色名` 按发言者删除。单独执行 `/del` 会打开批量选择。删除回复时可用 `toolcalls=off` 保留关联的工具调用记录。

### 自定义函数工具

原版 `/tools-register`、`/tools-list`、`/tools-invoke` 和 `/tools-unregister` 可在聊天和快捷回复中使用，也支持 `/tool-*` 别名。下面的脚本注册一个回显工具，手动执行后将结果送到下一条管道命令。

```stscript
/let key=echoSchema {"type":"object","properties":{"message":{"type":"string"}},"required":["message"]} |
/tools-register name=Echo displayName="回显" description="按用户要求复述一句话" parameters={{var::echoSchema}} {: /pass {{var::arg.message}} :} |
/tools-invoke parameters={"message":"你好，酒馆"} Echo | /echo {{pipe}}
```

开启「允许对话模型调用函数工具」后，符合条件的工具会提供给当前对话模型。执行闭包能读取注册时的局部变量。模型输入映射为 `{{var::arg.message}}` 等局部变量，支持 `arg.address.city` 和 `arg.items.0` 等路径。若要使用这些值调用子程序，请在脚本里写出目标闭包或快捷回复名称。

- `shouldRegister={: /getvar enabled :}` 按当前聊天变量决定模型能否看到该工具，`formatMessage={: /pass 正在处理 {{var::arg.message}} :}` 自定义运行提示。
- `stealth=true` 让工具执行后结束本轮工具流程，执行结果不写入「工具调用」消息。
- 注册结果保存在本次应用会话中。需要每次开启应用时注册的工具，可以把注册代码保存为全局启用的快捷回复，并勾选「启动应用」。用 `/tools-unregister Echo` 注销工具。

## 聊天图片和绘图

- 在「提示词预设 → 生成参数和模板」开启「在回复中请求图片」，然后在聊天顶部切换到支持图文输出的 Gemini 原生或 OpenRouter 模型。可在预设中设置比例和分辨率。模型返回的图片保存为聊天附件和图库项目，备选回复各自保留图片。可导出含图片的聊天 ZIP。 [Gemini 图像生成](https://ai.google.dev/gemini-api/docs/image-generation) · [OpenRouter 图像生成](https://openrouter.ai/docs/guides/overview/multimodal/image-generation)
- 聊天回复中的 PNG、JPEG、WebP 按原文件保存，单张最多 16 MB。Gemini 同一模型后续能接收已保存且能纳入请求的图文和签名；导入、换模型或编辑过文字的旧回复可作为普通参考。聊天请求的附件总额度仍为 12 MB。

- 在输入框旁点「添加图片」，通过 Android 文件选择器导入图片。每条消息最多附带 5 张，可发送纯图片消息。图片保存在应用内，支持图片输入的聊天模型会收到图片内容，例如 DeepSeek Flash。最近一次请求的提示词检查器会显示图片占位符。
- 在已发送的消息菜单可附加图片和本地 MP4。多张图片或视频可在列表和轮播间切换，选中的附件随聊天保存。可在预览中移除单个附件，也可从消息菜单移除当前回复里的全部媒体。 [原版消息媒体](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/chats.js)
- 可在「设置 → 外观」为尚未单独设置的消息选用默认列表或轮播。使用支持图片输入的模型时，列表仍会按附件额度带上可用图片，轮播只带上当前选中的图片。 [原版媒体显示](https://docs.sillytavern.app/usage/core-concepts/uicustomization/#media-style)
- 在输入框旁点「生成图片」，选择 Nano Banana 的日式二次元预设。最近剧情、角色全身立绘、角色头像、我的人设和对话背景都自带提示词与画面比例，应用会根据当前角色、人设或最近对话填充相应内容。可选填画面要求或启用草稿中的参考图。使用已保存且可调用 `gemini-nano-banana-2.1` 的 Google Gemini API Key，生成结果进入当前聊天及图库。
- 点「其他绘图选项」可手动编辑提示词、选择服务和画面比例，或填写其他兼容的 Gemini 绘图模型。可从聊天图片的预览中选「用于绘图」，或在绘图对话框选用草稿里已添加的图片作为参考。
- 自定义提供商可选 OpenAI 兼容的 `/images/generations`，也可选自托管的 AUTOMATIC1111 / Forge `/sdapi/v1/txt2img`。前者填写图像模型 ID，后者可留空使用服务当前的检查点。启用 `--api-auth` 的自托管服务可在提供商的 API Key 栏填写 `用户名:密码`。绘图完成后，结果保存在当前聊天，可在图片预览中另存。
- 在绘图窗口选择提示词来源并点「构建提示词」，可根据角色、人设、剧情或最近消息生成提示词，修改后再出图。也可在单条消息的菜单里用该消息绘图。对话背景模式会把结果设为当前对话背景，可从对话设置清除。
- 绘图消息下方的左右箭头切换备选图片。最后一张的右箭头会保留提示词与尺寸重新生成，固定种子改为随机种子。可从角色、群组或对话菜单查看图库，聊天附件 ZIP 会保存对话中的备选图、参考图与背景。
- 「绘图设置」可保存风格预设，编辑角色前缀和提示词模板，开启自然语言绘图与提示词扩写。绘图窗口的「设为默认绘图服务」会保存所选服务和生成参数。
- STscript 支持 `/sd`（别名 `/imagine`、`/img`、`/image`）、`/sd-source`、`/sd-style` 和 `/show-gallery`。例如 `/sd scene`、`/sd edit=true negative="watermark" seed=7 width=768 height=512 夕阳下的港口`，或使用 `/sd quiet=true 港口 | /send ![港口]({{pipe}})` 在后续脚本命令中发送绘图。

### 聊天视频

- 在单聊或群聊打开「对话设置 → 生成视频」，使用已有的 Google Gemini API Key 生成 3–15 秒短片。可填写提示词，选择横屏或竖屏、分辨率和时长，也可使用输入框中已添加的图片作为参考。调用 Gemini Omni Flash，请确保该 Key 已开通相应模型。 [Google Gemini 视频生成](https://aistudio.google.com/docs/omni)
- 生成结果保存到当前聊天。可在「对话设置」导入本地 MP4，也可在消息内播放、移除或另存视频。单个 MP4 上限为 128 MiB。
- 聊天及附件 ZIP 和本机账户备份包含视频文件。可把聊天 ZIP 导入角色或群组，也可从账户 ZIP 恢复聊天内的视频。普通 JSONL 保存视频引用。

### 图片描述

- 在「设置 → 图片描述」选择独立的描述来源。多模态方式支持 Google Gemini 和支持图片输入的 OpenAI 兼容模型，可设置提示词。手机本地方式使用原版默认的 ViT-GPT2 量化模型，安装约 246 MB 的权重后可离线生成英文描述。可在应用下载，或导入 `dist/Tavern-ViT-GPT2-Caption-Quantized.zip`。 [原版配置](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/default/config.yaml) [模型及许可](https://huggingface.co/Xenova/vit-gpt2-image-captioning/tree/077550ae0729f9af280c9d885ea9b0380fc8e815)
- 点草稿或历史消息的图片，进入「图片描述」即可生成、修改或手动填写。也可以在对话菜单用「从文件生成图片描述」。打开自动描述后，发送草稿前会补充未添加描述的图片，可选择先询问提示词或在应用前编辑结果。
- 打开「聊天模型仅接收图片文字描述」可将图片内容交给只接收文本的模型。描述保存在聊天记录和带附件 ZIP 中。在 STscript 可以用 `/caption mesId=-1 index=0 quiet=true` 将图片描述传给下一条命令。省略 `mesId` 时可从文件选择图片。

### 让对话模型调用绘图工具

1. 在「设置」的生成参数里开启「允许对话模型调用函数工具」，然后保存设置。
2. 在聊天里打开「生成图片 → 绘图设置」，开启「允许模型调用绘图工具」。可以编辑模型为绘图构建提示词时使用的说明。
3. 选择支持 Chat Completions 函数调用的对话模型和可用的绘图服务。发送「给我画一张雨后的街景」等请求，模型可调用 `GenerateImage`。图片进入聊天和图库，模型收到工具结果后继续回复。聊天中的「工具调用」消息可以展开查看参数和结果。

绘图函数使用绘图窗口保存的默认服务。使用带参考图占位符的 ComfyUI 工作流时，可以把引用图附在发给模型的消息里。绘图函数可用时优先由模型判断绘图意图。STscript 支持 `/tools-list return=pipe` 和 `/tools-invoke parameters={"prompt":"夕阳下的港口"} GenerateImage`。

### ComfyUI 工作流

1. 添加自定义提供商，填写 ComfyUI 的服务地址，例如 `http://192.168.1.20:8188`。默认模型 ID 可填写服务上的检查点文件名。使用 RunPod 时填写 `https://api.runpod.ai/v2/ENDPOINT_ID`、API Key 和 worker 使用的模型。
2. 在「设置 → ComfyUI 工作流」导入从 ComfyUI 导出的 API 格式 JSON，或复制内置工作流后编辑。生成窗口里选「ComfyUI」或「ComfyUI · RunPod」，再选工作流和「测试连接」。自托管端还可以点「读取服务参数」，挑选已有的检查点、采样器、调度器和 VAE。
3. 工作流可用 `%prompt%`、`%negative_prompt%`、`%width%`、`%height%`、`%model%`、`%sampler%`、`%scheduler%`、`%vae%`、`%steps%`、`%seed%`、`%scale%`、`%denoise%`、`%clip_skip%`、`%char_avatar%` 和 `%user_avatar%`。可添加自定义替换值，并在替换值中使用 `{{char}}` 和 `{{user}}`。内置头像工作流使用 `comfyui-tooling-nodes` 的 `ETN_LoadImageBase64` 节点。
4. 让 LoadImage 节点的文件名使用 `%reference_image%` 或 `%reference_image_1%` 至 `%reference_image_5%`，就能从聊天草稿或图片预览提供参考图。工作流可以导出为 API JSON，自定义替换值保存在应用内。
5. 可用 `/sd-source provider="我的 ComfyUI" comfy | /icw 我的工作流 | /sd seed=42 雨后的街道`，也可用 `/sd workflow="我的工作流" 雨后的街道` 单次指定工作流。RunPod 的来源名称是 `comfyrunpod`，`/icw` 是 `/imagine-comfy-workflow` 的别名。

本地生成使用 ComfyUI 队列。点击停止时，支持单任务取消接口的服务器会终止该任务，较旧的服务器可以移除仍在排队的任务。RunPod 使用该任务的取消接口。相关接口和文件格式见 [ComfyUI Server Routes](https://docs.comfy.org/development/comfyui-server/comms_routes) 与 [worker-comfyui](https://github.com/runpod-workers/worker-comfyui)。
- 在聊天顶部菜单选择「导出聊天及附件 ZIP」可把本机图片、对话背景、文档文本和消息一起迁移。到目标角色或群组的「导入聊天记录」打开 ZIP。普通 JSONL 携带消息和本机附件引用。
- 单张图片的导入上限为 16 MB。应用将图片最长边压到 2,048 像素以内并将单张保存大小限制在 2 MB。聊天模型请求最多附带 12 MB 的原始图片数据，优先附带最近消息里的图片。

- 聊天设置支持「气泡模式 / 文档小说模式」、字号与行距调节、中英文对话引号主题高亮、完整 Markdown（标题、引用、列表、表格、代码块一键复制、`<details>` 原生折叠），以及无网络隔离的 HTML/CSS 状态栏容器；输入框输入 `/` 或 `{{` 弹出 STscript 命令与宏自动补全，聊天顶部显示实时上下文 Token 占用条。

## 语音朗读和输入

- 点消息菜单的「朗读」播放该消息。在「设置 → 语音朗读与输入」可开启自动朗读，选择是否也读出自己的消息。流式回复保存后进入朗读队列。切换聊天、点击「停止朗读」或切到后台会停止播放。
- 支持系统音色、Microsoft Edge TTS（免 Key，内置多款中文/日语/英语音色）、ElevenLabs、Fish Audio、GPT-SoVITS 以及 OpenAI 兼容 `/audio/speech` 六种后端。密钥通过 Android Keystore AES-GCM 本地加密保存。
- 可以试听音色，设置语速和音高，为每个角色或人设指定音色。可设置仅朗读引号内容、过滤动作描写、代码块、标签内文字或匹配正则的内容。STscript 支持 `/speak`、`/narrate` 和 `/tts`，例如 `/speak voice="角色名" 欢迎来到酒馆`。
- 「Google Gemini TTS」沿用内置 Google 模板或自定义 Gemini 原生连接的 Key。可用 Gemini 3.8 Flash TTS 以及原版的 Gemini 3.1 和 2.5 TTS 模型，支持选择 30 种预设音色、试听和设置朗读风格。 [Gemini 语音生成文档](https://ai.google.dev/gemini-api/docs/speech-generation)
- 「NovelAI TTS」沿用已有的 NovelAI 自定义连接，支持预设音色和自定义种子。ElevenLabs 可读取并试听账号音色。ElevenLabs、Fish Audio 和 GPT-SoVITS 的地址与 Key 按引擎分别保存。 [原版 NovelAI 朗读](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/extensions/tts/novel.js) [ElevenLabs 音色接口](https://elevenlabs.io/docs/api-reference/voices/search)
- 「朗读引擎」可连接 VITS、Style-Bert-VITS2、XTTSv2、Silero 和 CosyVoice 的自建服务。填写手机可访问的服务地址，可读取音色、试听，并设为默认音色或角色、人设专属音色。各引擎分别保存地址、可选 API Key 和音色。VITS 还识别 W2V2-VITS、BERT-VITS2，Style-Bert-VITS2 可以为每个说话人选择风格，XTTSv2 可以选语言并调节采样参数。 [SillyTavern 1.19.1 朗读后端](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/public/scripts/extensions/tts)
- 点聊天输入框旁的麦克风开始识别，再点一次可结束录音。语音输入支持系统识别与 OpenAI 兼容 Whisper STT（16 kHz PCM16 录音上传至 `/audio/transcriptions`）。识别结果支持追加、替换或直接发送。语音快捷词每行用 `短语 = 预设文字或斜杠命令` 配置。

## 扩展生态、多后端与跨设备同步

- **多协议文本与聊天模型后端**：
  - 聊天补全内置提供商模板覆盖 DeepSeek、Google Gemini、Mistral、Cohere、Vertex AI（Express API Key 与 GCP OAuth Bearer）、Azure OpenAI（Deployment / `api-version`）、xAI（Grok）、Groq、Perplexity、Moonshot、Zhipu（GLM）与 NanoGPT，以及自定义 OpenAI 兼容、Anthropic Claude 原生（Prompt Caching、Extended Thinking、Assistant Prefill）和 OpenRouter。
  - 文本补全协议完整支持 TabbyAPI（含 `/v1/model/load` 与 `/v1/model/unload` 模型加载控制面板）、vLLM、Aphrodite、text-generation-webui（Oobabooga）、Featherless、Infermatic、DreamGen、Mancer、HuggingFace / TGI、KoboldCPP、Ollama、NovelAI 与 AI Horde。
  - **多 Key 轮询与故障切换**：每个提供商支持在 Android Keystore 中加密保存多把 API Key，支持轮询（Round-Robin）与错误自动故障切换（HTTP 401/402/403/429 及配额错误冷却重试），可在提供商编辑界面增删、排序、标注、隐藏与测试。
  - **多模型原生分词器**：除 DeepSeek V4.1 与 GPT（jtokkit）外，内置校准的 Mistral / Nemo、Qwen、Gemma、Claude、Command-R、Llama 1/2 与 Yi 原生分词配置，按模型 ID 自动匹配并支持在 Token 计数器与预算控制中切换。
  - **连接配置档案**：可通过「设置 → 连接配置档案」一键绑定模型、提示词预设与采样参数并按对话切换。
- **扩展绘图、语音与资料来源**：
  - 绘图与 `/sd source=...` 新增 Pollinations、Stability AI、Draw Things、SD.Next、Together AI、Imagen / Vertex、xAI、NanoGPT 与 HuggingFace Inference 后端（并保留 Gemini、OpenAI 兼容、A1111/Forge、ComfyUI/RunPod、NovelAI 与 AI Horde）。
  - 语音朗读新增 **Azure Speech TTS**、**AllTalk TTS** 与 **Kokoro TTS**，并支持自托管 **RVC 语音转换**后处理（模型选择、变调、Index Rate、Filter Radius）；语音输入新增基于 ONNX Runtime 的**本机离线 Whisper STT**。
  - 资料库（Data Bank）新增 **YouTube 字幕提取**、**Fandom Wiki** 与 **MediaWiki**（含 Wikipedia 及自定义 Wiki）导入器。
- **SillyTavern 1.19.x 完整特性与 STscript / 快捷回复调试器**：
  - 新增 `{{maxContextTokens}}`、`{{maxResponseTokens}}`、`{{defaultExpression}}`、`{{lastExpression}}`、`{{availableExpressions}}` 宏；新增 `/reasoning-collapse` 推理折叠控制；`/input`、`/popup`、`/buttons` 支持 `placeholder`、`tooltip` 与 `icon` 命名参数。
  - 背景库支持虚拟文件夹、网格/列表切换、面包屑导航与移动整理；外观设置支持原生高对比度（WCAG AAA）与减弱动态效果无障碍选项；重命名世界书时自动重连所有角色、群组、人设与对话引用。
  - 补齐 STscript 提示词注入（`/inject`、`/listinjects`、`/flushinject`，支持 `ephemeral`/`scan`/`filter`/`outlet`）、流程与匹配（`/abort`、`/fuzzy`、`/breakpoint` + 快捷回复单步调试器与 STscript 编辑器语法高亮）、人设/标签/临时聊天（`/persona`、`/persona-lock`、`/tempchat`、`/tag-add`/`-remove`/`-exists`/`-list`）、快捷回复 CRUD（`/qr-create`/`-update`/`-delete`/`-get`/`-list`、`/qrset-*`、`/qr-arg`）、配置与界面命令（`/api`、`/context`、`/instruct`、`/tokenizer`、`/vn`、`/theme`）以及全套字符串与数学工具命令。
- **TavernHelper、事件总线、MVU 引擎与内置扩展中心**：
  - 原生事件总线 `TavernEventBus` 覆盖 `MESSAGE_SENT`、`MESSAGE_RECEIVED`、`MESSAGE_EDITED`、`MESSAGE_DELETED`、`MESSAGE_SWIPED`、`GENERATION_STARTED`、`GENERATION_ENDED`、`CHAT_CHANGED`、`WORLDINFO_UPDATED` 与 `CHARACTER_EDITED`。
  - 完整 `window.TavernHelper` 沙盒桥接（变量 CRUD、消息 CRUD、世界书 CRUD、生成触发、事件订阅），默认零网络离线隔离并支持按角色卡配置权限与域名白名单，以及严格限定在卡片容器内的 CSS 作用域与 `<style>` 安全加固。
  - 独立 `MvuEngine` 支持 `[InitVar]` 初始化、`MvuSchema` 校验、`<UpdateVariable>`（`_.set`/`_.add`/`_.assign`/`_.unset` 与完整 JSON Patch）及 Swipe/编辑/删除确定性回滚重算。
  - 「设置 → 扩展中心」统一管理快捷提示词开关、对话状态变量检查器、角色设定助手、独立**联网搜索扩展**（SearXNG、Tavily、SerpApi、DuckDuckGo、Brave Search + `/websearch`）、**动态音频引擎**（BGM 与环境音效双通道循环/淡入淡出/关键词与表情自动切歌 + `/bgm`、`/ambient`）及**官方素材下载器**（带 HTTPS 主机白名单与 SHA-256 校验），所有扩展配置支持便携 JSON 导入导出并与设置快照和账户备份同步。
- **Android 平台原生增强与跨设备同步**：
  - **后台保活前台服务**：长时 LLM/绘图/视频生成通过前台服务通知保活并支持通知栏一键停止。
  - **系统分享菜单导入**：支持从其他应用通过 Android Share Sheet（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）直接导入 `.png`、`.json`、`.charx`、`.byaf` 角色卡。
  - **WebDAV 与局域网 SillyTavern 双向增量同步**：支持坚果云、Nextcloud、AList、NAS 等 WebDAV 服务双向增量同步角色卡、世界书、提示词预设与单聊记录，并支持局域网同步桌面端 SillyTavern。
  - **同世界书跨聊记忆 2.0**：结合关键词与本地 ONNX 向量（BGE/Jina）混合召回跨聊经历，支持在跨聊记忆面板置顶、编辑、屏蔽或一键沉淀为世界书条目。

## 对话翻译和摘要记忆

- 在消息菜单选择「翻译消息」或「显示原文」。聊天顶部的「对话设置」可翻译输入草稿、翻译整个对话或清除已显示的译文。可分别配置显示语言、发送语言和自动模式。用户消息的原文和实际发给模型的文本会随聊天保存。
- 在「对话设置 → 摘要记忆」可手动生成、编辑和恢复摘要，也可按新消息数量自动更新。摘要可插在主提示词前后，或按角色和深度进入聊天历史。摘要基于生成时的消息内容，编辑或删除相关历史后会退回仍有效的上一版。可在最近请求的提示词中检查注入结果。
- 自动更新支持按新增消息条数或词数触发，也可限制一次更新处理的消息条数。未处理的消息会留给下次更新。使用聊天模型时可设摘要目标词数，指令可用 `{{words}}`。开启「让最新摘要参与世界书扫描」后，摘要里的关键词也可触发条目。 [原版摘要设置](https://github.com/SillyTavern/SillyTavern/tree/1.19.1/public/scripts/extensions/memory)
- 在「设置 → 摘要服务」保存手机能访问的 SillyTavern Extras 服务地址及可选密钥，再在聊天的「摘要记忆」选择该来源。`/summarize source=extras 文本` 可直接调用 Extras，`source=main` 可指定聊天模型。Extras 密钥保存在本机的加密设置中。 [原版 Extras 摘要接口](https://github.com/SillyTavern/SillyTavern-Extras/blob/main/server.py)
- 在「设置 → 翻译服务」可测试并选择专用翻译来源，包括 Google 翻译、LibreTranslate、Lingva、DeepL Free／Pro、DeepLX、OneRingTranslator、Bing 和 Yandex 翻译。Google、Bing 和 Yandex 可使用原版兼容接口，也可填密钥使用各自的云 API。翻译密钥使用 Android Keystore 加密，聊天的语言和自动翻译选项随聊天保存。 [原版翻译扩展](https://docs.sillytavern.app/extensions/translation/)
- 选择聊天模型做翻译时，可独立指定已保存的提供商和模型，也可随当前对话切换。摘要记忆也可按对话选择独立的模型。更新摘要会附上上一版有效摘要及之后的文字和图片描述。修改相关描述会重新校验摘要。
- STscript 支持 `/translate target=zh-CN 待译文字` 和 `/summarize`。导入的 SillyTavern JSONL 可读取消息中的 `display_text` 与 `memory`，启用摘要注入后即可使用导入的摘要。

## 世界书向量检索和聊天召回

- 在「设置 → 向量检索」选择「手机本地（ONNX）」，然后下载或从文件导入所选模型。Jina v2 Base 英文版（约 109 MB）使用原版默认的量化模型。BGE Small v1.5 中文版（约 24 MB）适合中文记录。电脑下载的权重可从 `dist` 传到手机再导入。下载和导入会校验文件大小及 SHA-256。 [Jina 量化文件](https://huggingface.co/Cohee/jina-embeddings-v2-base-en/tree/e5e0a9ba66fd58c48e6f5a60293e7d1af2348225) [BGE 量化文件](https://huggingface.co/Xenova/bge-small-zh-v1.5/tree/75c43b069aac4d136ba6bc1122f995fedcfd2781)
- 本地模型可离线为世界书、聊天、资料库和消息文档计算向量。每个片段最多使用 512 个 Token，切换模型后按新模型建立索引。聊天生成时，选中的召回片段仍会随请求发给当前聊天模型。
- 远程方式支持已保存的 Google Gemini 或支持 `/embeddings` 的自定义提供商。Gemini 默认使用 `gemini-embedding-2`，自定义服务需要填写向量模型 ID。可通过「测试向量服务」验证本地模型或远程连接。使用远程服务时，查询和索引文本会发给所选服务。
- 世界书条目的「触发方式」可选择「向量匹配和关键词」。也可在向量设置中允许所有合适的条目参与语义匹配。语义命中仍遵守启用状态、角色与聊天范围、触发概率、冷却、互斥分组和注入额度。
- 聊天召回用最近消息查找语义相关的早期文本。可调整查询消息数、保留数量、相似度、分段长度和注入位置。命中的旧消息片段在本次请求中移到过去对话区，最近的消息按原顺序发送。检查「查看最近请求的提示词」可以看到召回结果。
- 在「对话设置 → 向量索引」可预先建立索引、查看覆盖数量或清除聊天和世界书索引。首次请求会逐批补齐未索引内容，后续会重用本机索引。向量服务不可用时，通常的聊天和关键词激活仍可使用。

## 资料库（Data Bank）

- 从「设置 → 资料库」管理全局或指定角色的资料。在「对话设置 → 资料库」还可以保存仅当前聊天使用的资料。群聊中，角色资料只参与该角色的回复。列表支持新建笔记、导入、编辑、禁用、导出文本和删除。
- 可导入 TXT、Markdown、HTML、ePUB 和其他可读纯文本，也可从网页地址提取文本。Android 15 及以上支持从有文字层的 PDF 中提取文本。单个资料支持最多 200 万字符，长篇资料可在列表导出全文。
- 资料库和单条消息附件均支持从 DOCX、XLSX、PPTX 及 OpenDocument 的 ODT、ODS、ODP 文件在手机上提取正文。工作簿会按原顺序提取工作表，并读取公式文件中保存的结果。演示文稿按幻灯片顺序提取文字。 [原版可转换的文档格式](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/chats.js)
- 「导入网页」支持每行粘贴一个网址，每批最多 20 个。每个成功提取正文的网页会保存为当前范围内的独立资料，失败时会显示结果和首个失败项。 [原版网页导入](https://github.com/SillyTavern/SillyTavern/blob/1.19.1/public/scripts/scrapers.js)
- 通过「资料检索设置」打开文件召回。配置好向量服务后，应用会在当前范围的资料中选取相关片段，按文档内的原顺序插入本次请求。可设置分段长度、重叠、插入位置，以及是否让召回文本参与世界书扫描。通过「建立索引」可批量预处理当前范围。
- STscript 支持 `/db`，以及 `/db-list`、`/db-add`、`/db-get`、`/db-update`、`/db-enable`、`/db-disable`、`/db-delete`。例如 `/db-list source=chat field=name`。

## 单条消息的文档附件

- 使用输入框旁的「添加文档」，或消息菜单的「为消息附加文档」。每条消息最多附带 5 份文档，也可以直接发送仅有文档的消息。聊天中可预览、移除或导出提取的全文，支持与图片一起发送。
- 模型请求会把附件文本放在对应消息内。每次请求最多加入 64,000 字符附件正文，优先处理最近的消息。可在「设置 → 向量检索」开启大文档片段检索，按相关性选取片段，再按文档内的原顺序发送。「对话设置 → 向量索引」可提前建立索引。
- 聊天及附件 ZIP 可保存本机文档文本与图片。从已有 SillyTavern 实例导入新聊天时，应用也会尝试从同一服务器读取标准文档路径。原版 JSONL 自带的内嵌文档文本可单独导入。

Tavern Native 1.0.0 已达成 SillyTavern 1.19.1 核心功能、后端协议、STscript、多媒体语音、资料来源与内置扩展生态的全量 Android 原生覆盖，并通过 10,000+ 消息长对话分页与全链路数据库迁移压力测试验收。更新记录详见 [CHANGELOG.md](CHANGELOG.md)。

## 从源码构建

使用 JDK 17 和 Android SDK API 37、Build Tools 36.0.0。在 Android Studio、`ANDROID_HOME` 或 `local.properties` 中配置 SDK 路径。

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

调试包位于 `app/build/outputs/apk/debug/`。发布构建默认生成未签名包，可用自己的密钥签名。已签名的交付包在 `dist/`。

项目代码采用仓库中的 AGPL-3.0 许可证。正则引擎的 Joni 和 JCodings 许可证随 APK 打包。DeepSeek V4.1 分词表按 MIT 许可分发，源文件来自 [deepseek-recipe](https://github.com/deepseek-ai/deepseek-recipe/tree/main/static/tokenizers/v41)，构建脚本为 `engine/build-lore-tokenizer.py`。

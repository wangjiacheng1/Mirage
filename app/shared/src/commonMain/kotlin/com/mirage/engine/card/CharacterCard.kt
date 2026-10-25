package com.mirage.engine.card

import kotlinx.serialization.json.JsonObject

/**
 * ============================================================================
 * CharacterCard —— 一张导入进来的角色卡
 * ============================================================================
 *
 * 角色卡就是一个 JSON 文件，里面装着：这个角色是谁、开场说什么、
 * 世界观设定有哪些条目、状态栏一开始都是多少。
 * 我们把它读成这个对象，后面拼正文提示词和初始化状态栏都从这里取。
 *
 * ## 为什么要把原始内容整份留着（[raw]）
 *
 * 卡的格式有好几个版本，字段也不止我们认识的这些。只认识自己要用的那些、
 * 其余丢掉的话，用户导出时卡就变样了 —— 他从别处拿来的卡，在我们这里转一圈
 * 之后回不到原样，这是不能接受的。所以认识的字段单独取出来用，
 * 整份原文也留着，导出时以原文为底，只把我们确实改过的地方覆盖回去。
 */
data class CharacterCard(
    /** 角色名。 */
    val name: String,

    /** 角色的长设定，通常是最主要的一段。 */
    val description: String = "",

    /** 性格描述。 */
    val personality: String = "",

    /** 场景/处境描述。 */
    val scenario: String = "",

    /** 开场第一条消息。导入后界面上先显示它，不花钱也不用调模型。 */
    val firstMessage: String = "",

    /** 对话示例，用来给模型示范说话的样子。 */
    val messageExample: String = "",

    /** 卡作者写的系统提示词。可能为空。 */
    val systemPrompt: String = "",

    /** 卡作者写的「放在历史最后」的提示词。可能为空。 */
    val postHistoryInstructions: String = "",

    /** 备选开场消息。用户可以挑一个替换默认开场。 */
    val alternateGreetings: List<String> = emptyList(),

    /** 标签。 */
    val tags: List<String> = emptyList(),

    /** 作者给用户看的说明。 */
    val creatorNotes: String = "",

    /** 作者名。 */
    val creator: String = "",

    /** 卡自己的版本号，和格式版本不是一回事。 */
    val characterVersion: String = "",

    /** 世界观设定条目。没有就是 null。 */
    val worldBook: WorldBook? = null,

    /** 格式版本。不同版本字段略有差别，取值时要兼容。 */
    val spec: CardSpec = CardSpec.Unknown,

    /** 格式版本的原始字符串，原样留着备查。 */
    val specVersion: String = "",

    /** 整份原文。导出时以它为底，保证卡转一圈不变样。 */
    val raw: JsonObject,
)

/** 角色卡的格式版本。 */
enum class CardSpec {
    /** 最早的格式：字段直接摊在最外层，没有 data 这一层。 */
    V1,

    /** 带 data 包装的格式。 */
    V2,

    /** V2 的扩展版，多了作者、版本号等字段。 */
    V3,

    /** 认不出来的版本。仍然尽量按 V2 的方式去读，读到什么算什么。 */
    Unknown,
}

/**
 * 世界观设定本。
 *
 * 它是一组条目，每条带一串触发词。聊天时哪条被提到了，就把那条的内容
 * 塞进这次请求里；另外还有一类条目是「每次都带上」的。
 */
data class WorldBook(
    val name: String = "",
    val entries: List<WorldBookEntry> = emptyList(),
)

/**
 * 世界观设定里的一条。
 *
 * @property keys 触发词。聊天内容里出现其中任意一个，这条就会被带上。
 * @property content 这条的正文内容。
 * @property constant 是否每次都带上，不看触发词。规则类条目一般都是这种。
 * @property enabled 作者可以把某条关掉但仍留在卡里。关掉的不要往请求里塞。
 * @property insertionOrder 多条同时命中时的排列次序，数字小的排前面。
 * @property position 插在哪个位置。各版本写法不统一，所以原样留着字符串，
 *   同时给一个归一后的取值，认不出来就落到「未知」。
 * @property comment 作者给自己看的备注。它很有用 —— 存放状态栏初值的那一条，
 *   作者通常就在这里标了记号。
 */
data class WorldBookEntry(
    val id: Int? = null,
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val comment: String = "",
    val constant: Boolean = false,
    val enabled: Boolean = true,
    val selective: Boolean = false,
    val insertionOrder: Int = 0,
    val positionRaw: String = "",
    val position: WorldBookPosition = WorldBookPosition.Unknown,
)

/**
 * 条目插入位置。
 *
 * 各版本的写法不统一（有的写数字，有的写名字），所以只归一成我们认识的几种，
 * 认不出来就是 [Unknown] —— 这时按「跟在角色设定后面」处理，这是最常见的一种。
 */
enum class WorldBookPosition {
    /** 放在系统提示词之前。 */
    BeforeSystem,

    /** 放在系统提示词之后。 */
    AfterSystem,

    /** 放在角色设定之前。 */
    BeforeCharacter,

    /** 放在角色设定之后。 */
    AfterCharacter,

    /** 按深度插在聊天记录中间。 */
    AtDepth,

    Unknown,
}

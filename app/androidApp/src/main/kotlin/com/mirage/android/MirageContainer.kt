package com.mirage.android

import android.content.Context
import com.mirage.engine.credential.AndroidKeystoreCredentialStore
import com.mirage.engine.credential.CredentialStore
import com.mirage.engine.llm.ChatCompletionClient
import com.mirage.engine.net.createAndroidTransport
import com.mirage.engine.settlement.SettlementCoordinator

/**
 * 把各个组件装配起来。
 *
 * 现在用手写装配而不是引入依赖注入框架：当前只有一个界面、两个对象，
 * 框架带来的配置量比它省下的还多，而且多一层间接会让「这个对象到底是怎么来的」
 * 变得不直观。在结构还没稳定的阶段，直观比省代码重要。
 * 等界面和依赖多起来再换，调用方不受影响。
 *
 * 整个应用只建一份：底层网络客户端持有连接池，重复创建会浪费连接。
 */
class MirageContainer(context: Context, debugBuild: Boolean) {

    /** 记住上次选过的接入点与模型名。非机密，不走加密通道。 */
    val selectionStore: SelectionStore = SelectionStore(context)

    /** 凭据存放。交给系统密钥库保护，我们自己拿不到密钥材料。 */
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(context)

    /**
     * 对话客户端。
     *
     * 诊断输出只在调试构建下开启。
     */
    val chatClient: ChatCompletionClient = ChatCompletionClient(
        createAndroidTransport(enableLogging = debugBuild),
    )

    /**
     * 结算执行器。
     *
     * 复用同一个对话客户端：两条通道（正文、结算）底层是同一种调用，
     * 各建一套只会多出一份连接池和一份需要单独维护的解析逻辑。
     */
    val settlementCoordinator: SettlementCoordinator = SettlementCoordinator(chatClient)
}

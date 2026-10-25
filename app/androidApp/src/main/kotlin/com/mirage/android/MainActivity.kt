package com.mirage.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 唯一的界面宿主。
 *
 * 当前只有一屏，所以不引入页面导航 —— 多一层导航就多一套状态保存和返回栈规则，
 * 在只有一屏时纯属负担。等真的有第二屏再加。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MirageApp).container

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 状态持有者交给宿主管理，这样旋转屏幕时进行中的请求不会被打断
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModel.Factory(container),
                    )
                    ChatScreen(viewModel = chatViewModel)
                }
            }
        }
    }
}

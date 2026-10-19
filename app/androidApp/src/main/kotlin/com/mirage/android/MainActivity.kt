package com.mirage.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirage.engine.state.Disposition
import com.mirage.engine.state.FieldSpec
import com.mirage.engine.state.FieldType
import com.mirage.engine.state.StateGuards
import com.mirage.engine.state.StateSchema
import com.mirage.engine.state.StateValue
import com.mirage.engine.state.displayText

/**
 * ============================================================================
 * MainActivity —— M1 阶段的临时「守卫自检」页面
 * ============================================================================
 *
 * 这**不是**产品 UI，正式 UI 要等设计师出稿（docs/03）。
 *
 * 它存在的唯一目的：让我们能在真机上一眼确认
 * 「引擎模块真的被打进 APK 了、G1~G8 真的在跑」。
 *
 * 为什么值得花这十分钟写它：KMP 工程第一次跑通时，最常见的坑是
 * 「:shared 编译过了但没真正链进 APK」。有这个页面，装上就知道。
 * 等 M2 真 UI 进来，这个文件整个删掉。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GuardSelfCheckScreen()
                }
            }
        }
    }
}

@Composable
private fun GuardSelfCheckScreen() {
    // 用 M0 实测里真实出现过的那一批提议跑一遍守卫
    val schema = StateSchema(
        fields = mapOf(
            "冒险者/生命" to FieldSpec(
                FieldType.Int, min = 0.0, maxFromPath = "冒险者/生命上限", displayName = "生命",
            ),
            "冒险者/生命上限" to FieldSpec(FieldType.Int, displayName = "生命上限"),
            "地牢/当前轮" to FieldSpec(FieldType.Int, readOnly = true, displayName = "当前轮"),
            "地牢/本轮房间" to FieldSpec(
                FieldType.String,
                enumValues = setOf("门厅", "回廊", "石室"),
                displayName = "本轮房间",
            ),
        ),
    )

    val receipt = StateGuards.apply(
        turn = 3,
        current = mapOf(
            "冒险者/生命" to StateValue.IntValue(60),
            "冒险者/生命上限" to StateValue.IntValue(60),
            "地牢/当前轮" to StateValue.IntValue(2),
            "地牢/本轮房间" to StateValue.StringValue("门厅"),
        ),
        proposals = mapOf(
            "冒险者/生命" to StateValue.IntValue(49),        // ✅ 正常
            "地牢/当前轮" to StateValue.IntValue(3),         // ❌ G6 只读
            "冒险者/魔法值" to StateValue.IntValue(10),      // ❌ G1 未知路径
            "地牢/本轮房间" to StateValue.StringValue("战斗"), // ❌ G4 非法枚举
        ),
        schema = schema,
        summary = "冒险者被灰狼咬伤。",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Mirage · 守卫自检", style = MaterialTheme.typography.titleLarge)
        Text(
            "状态：${receipt.status}（预期 Partial）",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        receipt.summary?.let {
            Text("原因：$it", style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "✅ 已落盘 ${receipt.changes.size} 项",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        receipt.changes.forEach { c ->
            // 这个格式刻意对齐 DSH Tavern 的回执：`设置 路径  60 → 49`
            Text("${c.path}  ${c.before.displayText()} → ${c.after.displayText()}")
        }

        Text(
            "⛔ 已拦下 ${receipt.failures.count { it.disposition == Disposition.Rejected }} 项",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        receipt.failures.forEach { f ->
            Text("[${f.code}] ${f.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

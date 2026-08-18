package com.yuanqian.autofarm.presentation.view.workshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 脚本工坊使用指南（分页介绍所有功能）。
 * 点击工坊页"脚本工坊"旁的 ⓘ 打开。
 */
@Composable
fun WorkshopGuideDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        var page by remember { mutableIntStateOf(0) }
        val pages = guidePages()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖 脚本工坊使用指南", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${page + 1}/${pages.size}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            // 页签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pages.forEachIndexed { idx, p ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (idx == page) Color(0xFF3A6EA5) else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.title,
                            color = if (idx == page) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 内容
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pages[page].lines.forEach { line ->
                        val isTitle = line.startsWith("#")
                        Text(
                            text = line.removePrefix("#"),
                            color = if (isTitle) Color(0xFF64B5F6) else Color.White.copy(alpha = 0.85f),
                            fontSize = if (isTitle) 14.sp else 13.sp,
                            fontWeight = if (isTitle) FontWeight.Bold else FontWeight.Normal,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 底部操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (page > 0) page-- }) {
                    Text("← 上一页", color = if (page > 0) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f))
                }
                TextButton(onClick = onClose) {
                    Text("关闭", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
                TextButton(onClick = { if (page < pages.size - 1) page++ }) {
                    Text("下一页 →", color = if (page < pages.size - 1) Color.White.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.3f))
                }
            }
        }
    }
}

private data class GuidePage(val title: String, val lines: List<String>)

private fun guidePages(): List<GuidePage> = listOf(
    GuidePage(
        "总览",
        listOf(
            "# 脚本工坊是什么",
            "可视化制作自动化流程：把「节点」拖到画布，用「连线」决定执行顺序和分支，像搭积木一样拼出脚本。",
            "",
            "# 流程三要素",
            "节点：做什么（执行/判定/控制）",
            "连线：怎么走（顺序/是/非/与/或）",
            "突发：紧急打断（始终监听的特殊判定）",
            "",
            "# 流程怎么跑",
            "后台任务页「引用流程」或「配置绑定流程」→ 点开始任务 → FlowEngine 按连线逻辑在虚拟屏上执行。",
        ),
    ),
    GuidePage(
        "节点",
        listOf(
            "# 执行节点（做事）",
            "启动应用：打开指定应用到虚拟屏",
            "点击：按坐标或模板中心点按",
            "滑动：起点→终点，可设时长",
            "返回：按返回键 N 次",
            "等待：固定时长 / 直到识别到某模板",
            "文本输入：向屏幕输入文字（新版）",
            "长按：按住指定时长（新版）",
            "",
            "# 判定节点（输出是/非）",
            "图像识别：模板是否命中",
            "应用状态：应用是否在前台/存活（新版）",
            "",
            "# 控制节点（管流转）",
            "循环：次数循环 / 直到条件（新版）",
            "",
            "# 自定义节点",
            "通过 JSON 模板定义自己的节点（执行/判定/控制），可带参数和执行命令。",
        ),
    ),
    GuidePage(
        "连线",
        listOf(
            "# 连线类型",
            "顺序：普通箭头线，按顺序执行",
            "是：绿色流动 >>>，判定命中走这里",
            "非：红色虚线，判定未命中走这里",
            "与：多开端全满足才继续（黄色）",
            "或：多开端任一满足即继续（蓝色）",
            "",
            "# 怎么连",
            "工具栏点「连线」→ 点开端节点（可多选）→ 选类型（顺序/是/非/与/或）→ 点末端节点。",
            "",
            "# 无线自动顺序",
            "节点没有连线时，执行完会自动走到列表中下一个节点（顺序语义），最后节点结束。",
        ),
    ),
    GuidePage(
        "突发",
        listOf(
            "# 突发是什么",
            "始终监听的特殊判定：流程跑着跑着，突然出现某个画面（如弹窗/警告）→ 立即中断主流程，执行应急区间，再跳回继续。",
            "",
            "# 怎么设置",
            "工具栏点「突发」→ 依次点：判定节点（🚦）→ 应急区间节点（⚠️）→ 命中后继续节点（🔵）→ 确认突发。",
            "",
            "# 说明",
            "突发可设「始终监听」：整个流程期间都生效。",
            "命中执行的区间按节点顺序执行。",
        ),
    ),
    GuidePage(
        "识别模板",
        listOf(
            "# 模板是什么",
            "截图中的一小块特征图，用于「图像识别」节点判断画面上有没有出现它。",
            "",
            "# 怎么做模板",
            "工坊页点「模板库」→ 导入截图（或拍照）→ 框选目标区域 → 命名保存。",
            "",
            "# 识别节点怎么用",
            "图像识别节点选模板 + 阈值（0.8 左右常用）→ 命中走「是」线，未命中走「非」线。",
            "可设重试次数和间隔（识别失败后自动重试）。",
        ),
    ),
    GuidePage(
        "自定义节点",
        listOf(
            "# 什么是自定义节点",
            "用 Shell 命令写自己的节点，全局通用：一个流程里创建，所有流程都能用。",
            "",
            "# 创建（本地新建）",
            "工坊工具栏「🧩自定义」→ 本地新建：填名称、选类型（执行/判定/控制）、写节点代码。",
            "",
            "# 节点代码示例",
            "时间节点样式：sleep 1000（等待1秒）",
            "点击：input tap 500 800",
            "判定类：命令退出码 0=成功→走「是」线，非0→走「非」线",
            "",
            "# 使用",
            "创建后自动出现在对应下拉栏（执行/判定/控制）最下面，带 🧩 标记。",
            "",
            "# 管理",
            "🧩 面板：点=添加节点；右侧「删除」=直接删除；长按=导出 .json / 删除。",
            "",
            "# 导入 / 导出",
            "导入：面板「导入文件」选 .json 模板；或直接导入别人导出的流程文件夹。",
            "导出：长按自定义节点→导出文件；导出流程时会自动附带用到的自定义节点。",
            "",
            "# 导入流程提示",
            "导入的流程用到本地没有的自定义节点时，会提示「未装配」；导入对应模板后自动生效。",
        ),
    ),
    GuidePage(
        "流程管理",
        listOf(
            "# 新建",
            "输入流程名 → 新建，自动生成「流程信息」节点。",
            "",
            "# 导入 / 导出",
            "导出：完整格式（节点/连线/突发/模板），文件名=流程名.json",
            "导入：自动识别两种格式（工坊完整格式 / 旧版 pipeline），旧版会补 INFO 节点和顺序连线。",
            "",
            "# 改名 / 删除",
            "流程卡片上操作，改名会同步文件夹。",
            "",
            "# 绑定配置",
            "后台任务 → 配置管理 → 🔗绑定流程：配置成为流程的镜像，流程更新自动同步到配置。",
        ),
    ),
    GuidePage(
        "运行",
        listOf(
            "# 运行方式",
            "后台任务页：引用流程（新建配置）或绑定流程（自动同步）→ 开始任务。",
            "",
            "# 执行引擎",
            "FlowEngine 按连线逻辑执行：识别用模板匹配，动作走虚拟屏触摸，突发始终监听。",
            "",
            "# 看效果",
            "后台任务页顶部有虚拟屏实时预览，运行时可看到画面和触摸轨迹；日志页看执行记录。",
            "",
            "# 调试",
            "编辑器里有「运行」按钮可直接试跑当前流程；「导出」生成完整流程文件可分享。",
        ),
    ),
)

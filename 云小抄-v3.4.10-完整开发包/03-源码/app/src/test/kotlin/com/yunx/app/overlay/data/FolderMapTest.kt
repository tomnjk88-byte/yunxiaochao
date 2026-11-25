/*
 * 云小抄（YunX Guide Overlay）
 * 基于 云析 YunX（https://github.com/CYQawa/YunX）二次开发。
 * Copyright (C) 2026 CYQawa（原项目作者） / 2026 <你的名字>
 *
 * AGPL-3.0 衍生作品，完整协议见项目根目录 LICENSE。
 * 修改说明：新增文件（v2.0.3）。下载目录复刻的纯逻辑测试：
 * 目录名清洗、路径推导、脏数据防护。
 */

package com.yunx.app.overlay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderMapTest {

    private fun node(id: String, parent: String, name: String) =
        FolderPaths.Node(id = id, parentId = parent, localName = name)

    // ------------------------------------------------------------------
    // 目录名清洗
    // ------------------------------------------------------------------

    @Test
    fun `中文和常见符号原样保留`() {
        assertEquals("攻略合集", FolderPaths.sanitize("攻略合集"))
        assertEquals("第1页（新版）", FolderPaths.sanitize("第1页（新版）"))
        assertEquals("A-B_C", FolderPaths.sanitize("A-B_C"))
        assertEquals("地图 v2", FolderPaths.sanitize("地图 v2"))
    }

    @Test
    fun `路径分隔符和文件系统保留字符换成下划线`() {
        // 不换的话，`a/b` 会变成一层子目录，或者直接创建失败
        assertEquals("a_b", FolderPaths.sanitize("a/b"))
        assertEquals("a_b_c_d_e_f_g_h_i", FolderPaths.sanitize("a\\b:c*d?e\"f<g>h|i"))
    }

    @Test
    fun `结尾的点和空格去掉`() {
        // Windows / 部分 SAF provider 会把这类名字规范化或者直接拒绝
        assertEquals("攻略", FolderPaths.sanitize("攻略..."))
        assertEquals("攻略", FolderPaths.sanitize("攻略   "))
    }

    @Test
    fun `开头的点去掉，不建隐藏目录`() {
        assertEquals("缓存", FolderPaths.sanitize(".缓存"))
        assertEquals("缓存", FolderPaths.sanitize("  .缓存  "))
    }

    @Test
    fun `空名和点点走兜底`() {
        assertEquals("未命名", FolderPaths.sanitize(""))
        assertEquals("未命名", FolderPaths.sanitize("   "))
        assertEquals("未命名", FolderPaths.sanitize(".."))
        // 有 fid 时用短哈希，保证同名兜底不会撞在一起
        val fallback = FolderPaths.sanitize("", "fid-123456")
        assertTrue(fallback.startsWith("目录_"))
        assertEquals(fallback, FolderPaths.sanitize("", "fid-123456"))
    }

    @Test
    fun `超长名字按字符截断`() {
        val long = "攻".repeat(100)
        assertEquals(FolderPaths.MAX_SEGMENT, FolderPaths.sanitize(long).length)
    }

    @Test
    fun `截断不会把 emoji 劈成半个`() {
        // 🗺 是代理对，按 char 截断会留下一个孤立的高位代理，写进 SAF 直接抛异常
        val name = "🗺".repeat(60)
        val out = FolderPaths.sanitize(name)
        assertTrue(out.codePointCount(0, out.length) <= FolderPaths.MAX_SEGMENT)
        // 没有落单的代理项：落单写进 SAF 会直接抛 IllegalArgumentException
        var i = 0
        while (i < out.length) {
            if (Character.isHighSurrogate(out[i])) {
                assertTrue(i + 1 < out.length && Character.isLowSurrogate(out[i + 1]))
                i += 2
            } else {
                i++
            }
        }
    }

    // ------------------------------------------------------------------
    // 路径推导
    // ------------------------------------------------------------------

    @Test
    fun `分类直接挂在根下`() {
        val nodes = mapOf("c1" to node("c1", "root", "攻略"))
        val path = FolderPaths.resolvePath("c1", { nodes[it] })
        assertEquals(listOf("攻略"), path)
    }

    @Test
    fun `两层目录从根往下排`() {
        val nodes = mapOf(
            "c1" to node("c1", "root", "攻略"),
            "s1" to node("s1", "c1", "第一章")
        )
        val path = FolderPaths.resolvePath("s1", { nodes[it] })
        assertEquals(listOf("攻略", "第一章"), path)
    }

    @Test
    fun `未知目录返回空，落在根目录`() {
        // 链接源 / 刚换的分享 fid 都不在表里，这时候平铺比猜一个名字安全
        assertEquals(emptyList<String>(), FolderPaths.resolvePath("nope", { null }))
        assertEquals(emptyList<String>(), FolderPaths.resolvePath(null, { null }))
        assertEquals(emptyList<String>(), FolderPaths.resolvePath("", { null }))
    }

    @Test
    fun `父目录不在表里就停在当前层`() {
        // 分类的父是「我的小抄」根目录，根不是分类，本来就不该出现在路径里
        val nodes = mapOf("c1" to node("c1", "root-fid", "攻略"))
        assertEquals(listOf("攻略"), FolderPaths.resolvePath("c1", { nodes[it] }))
    }

    @Test
    fun `父指向自己时立刻停`() {
        val nodes = mapOf("c1" to node("c1", "c1", "攻略"))
        assertEquals(listOf("攻略"), FolderPaths.resolvePath("c1", { nodes[it] }))
    }

    @Test
    fun `脏数据成环不会死循环`() {
        val nodes = mapOf(
            "a" to node("a", "b", "A"),
            "b" to node("b", "a", "B")
        )
        val path = FolderPaths.resolvePath("a", { nodes[it] })
        // 能返回就行，具体取到哪几层不重要；绝不能卡住
        assertTrue(path.size == 2)
        assertEquals(listOf("B", "A"), path)
    }

    @Test
    fun `超过最大层数就截断`() {
        val nodes = mapOf(
            "1" to node("1", "root", "一层"),
            "2" to node("2", "1", "二层"),
            "3" to node("3", "2", "三层"),
            "4" to node("4", "3", "四层")
        )
        val path = FolderPaths.resolvePath("4", { nodes[it] })
        assertEquals(FolderPaths.MAX_DEPTH, path.size)
        // 保留离当前目录最近的几层（最外层被压平）
        assertEquals(listOf("二层", "三层", "四层"), path)
    }

    @Test
    fun `空名字的节点不占一层`() {
        val nodes = mapOf(
            "c1" to node("c1", "root", ""),
            "s1" to node("s1", "c1", "第一章")
        )
        assertEquals(listOf("第一章"), FolderPaths.resolvePath("s1", { nodes[it] }))
    }

    @Test
    fun `深度上限可调`() {
        val nodes = mapOf(
            "1" to node("1", "root", "一层"),
            "2" to node("2", "1", "二层")
        )
        assertEquals(listOf("二层"), FolderPaths.resolvePath("2", { nodes[it] }, 1))
    }

    // ------------------------------------------------------------------
    // 全量展开（v2.0.4：把整棵树建到本地）
    // ------------------------------------------------------------------

    @Test
    fun `整棵树展开成每条完整路径`() {
        val nodes = mapOf(
            "c1" to node("c1", "root", "攻略"),
            "c2" to node("c2", "root", "地图"),
            "s1" to node("s1", "c1", "第一章"),
            "s2" to node("s2", "c1", "第二章")
        )
        val paths = FolderPaths.allPaths(nodes.keys, { nodes[it] })
        assertEquals(
            listOf(
                listOf("地图"),
                listOf("攻略"),
                listOf("攻略", "第一章"),
                listOf("攻略", "第二章")
            ),
            paths
        )
        // 父目录一定排在自己的子目录前面 —— 建目录要按这个顺序，否则子目录会先失败
        val flat = paths.map { it.joinToString("/") }
        assertEquals(flat.sorted(), flat)
    }

    @Test
    fun `未知 fid 不进结果，不会在根下造空名目录`() {
        val nodes = mapOf("c1" to node("c1", "root", "攻略"))
        val paths = FolderPaths.allPaths(listOf("c1", "ghost"), { nodes[it] })
        assertEquals(listOf(listOf("攻略")), paths)
    }

    @Test
    fun `同名同父的两条路径只建一次`() {
        // 正常情况 uniqueName 会加 _2 区分；这里直接灌脏数据，验证展开阶段不会重复
        val nodes = mapOf(
            "a" to node("a", "root", "攻略"),
            "b" to node("b", "root", "攻略")
        )
        val paths = FolderPaths.allPaths(nodes.keys, { nodes[it] })
        assertEquals(listOf(listOf("攻略")), paths)
    }

    @Test
    fun `成环的脏数据展开也不会卡住`() {
        val nodes = mapOf(
            "a" to node("a", "b", "A"),
            "b" to node("b", "a", "B")
        )
        val paths = FolderPaths.allPaths(nodes.keys, { nodes[it] })
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.size <= FolderPaths.MAX_DEPTH })
    }

    @Test
    fun `空输入返回空表`() {
        assertEquals(emptyList<List<String>>(), FolderPaths.allPaths(emptyList(), { null }))
    }
}

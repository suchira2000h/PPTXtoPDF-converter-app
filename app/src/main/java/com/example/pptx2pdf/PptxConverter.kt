package com.example.pptx2pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Rel(val type: String, val target: String)
class Ctx(val layoutTree: Element?, val masterTree: Element?, val master: Element?)

private class SizeSpan(val size: Float) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.textSize = size }
    override fun updateMeasureState(tp: TextPaint) { tp.textSize = size }
}

class PptxConverter(private val files: Map<String, ByteArray>) {

    companion object {
        const val EMU = 12700f
        const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        fun readZip(input: InputStream): Map<String, ByteArray> {
            val m = HashMap<String, ByteArray>()
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory) m[e.name] = z.readBytes()
                    e = z.nextEntry
                }
            }
            return m
        }
    }

    private val themeColors = HashMap<String, Int>()

    // ---------- XML helpers ----------

    private fun xml(path: String): Element? {
        val b = files[path] ?: return null
        return try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            f.newDocumentBuilder().parse(ByteArrayInputStream(b)).documentElement
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.kids(name: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && (name == null || n.localName == name)) out.add(n as Element)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.kid(name: String): Element? = kids(name).firstOrNull()

    private fun Element.at(vararg names: String): Element? {
        var e: Element? = this
        for (n in names) e = e?.kid(n)
        return e
    }

    private fun Element.num(attr: String): Long? = getAttribute(attr).toLongOrNull()

    private fun pickAttr(list: List<Element?>, name: String): String {
        for (e in list) {
            val v = e?.getAttribute(name)
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    // ---------- Relationships ----------

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (s in p.split('/')) {
            if (s.isEmpty() || s == ".") continue
            if (s == "..") {
                if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            } else {
                parts.add(s)
            }
        }
        return parts.joinToString("/")
    }

    private fun rels(partPath: String): Map<String, Rel> {
        val dir = partPath.substringBeforeLast('/', "")
        val name = partPath.substringAfterLast('/')
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
        val root = xml(relPath) ?: return emptyMap()
        val m = HashMap<String, Rel>()
        for (r in root.kids("Relationship")) {
            val t = r.getAttribute("Target")
            val full = if (t.startsWith("/")) t.substring(1) else normalize("$dir/$t")
            m[r.getAttribute("Id")] = Rel(r.getAttribute("Type"), full)
        }
        return m
    }

    // ---------- Colors ----------

    private fun loadTheme() {
        val root = xml("ppt/theme/theme1.xml") ?: return
        val scheme = root.getElementsByTagNameNS("*", "clrScheme").item(0) as? Element ?: return
        for (c in scheme.kids()) {
            val v = c.kids().firstOrNull() ?: continue
            val hex = if (v.localName == "sysClr") v.getAttribute("lastClr") else v.getAttribute("val")
            parseHex(hex)?.let { themeColors[c.localName] = it }
        }
    }

    private fun parseHex(h: String): Int? =
        if (h.length == 6) try { Color.parseColor("#$h") } catch (e: Exception) { null } else null

    private fun colorOf(parent: Element?): Int? {
        val fill = parent?.kid("solidFill") ?: return null
        return colorFrom(fill)
    }

    /** `holder`'s first child is a color element (srgbClr / schemeClr / sysClr). */
    private fun colorFrom(holder: Element): Int? {
        val c = holder.kids().firstOrNull() ?: return null
        val base: Int = when (c.localName) {
            "srgbClr" -> parseHex(c.getAttribute("val"))
            "sysClr" -> parseHex(c.getAttribute("lastClr"))
            "schemeClr" -> {
                val k = when (val v = c.getAttribute("val")) {
                    "tx1" -> "dk1"
                    "tx2" -> "dk2"
                    "bg1" -> "lt1"
                    "bg2" -> "lt2"
                    else -> v
                }
                themeColors[k]
            }
            else -> null
        } ?: return null

        var r = Color.red(base)
        var g = Color.green(base)
        var b = Color.blue(base)
        val mod = c.kid("lumMod")?.num("val")
        val off = c.kid("lumOff")?.num("val")
        if (mod != null || off != null) {
            val m = (mod ?: 100000L) / 100000f
            val o = (off ?: 0L) / 100000f
            r = (r * m + 255 * o).toInt().coerceIn(0, 255)
            g = (g * m + 255 * o).toInt().coerceIn(0, 255)
            b = (b * m + 255 * o).toInt().coerceIn(0, 255)
        }
        val a = ((c.kid("alpha")?.num("val") ?: 100000L) / 100000f * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    // ---------- Geometry / placeholder inheritance ----------

    private fun xfrmRect(x: Element?): RectF? {
        x ?: return null
        val off = x.kid("off") ?: return null
        val ext = x.kid("ext") ?: return null
        val l = off.num("x") ?: return null
        val t = off.num("y") ?: return null
        val cx = ext.num("cx") ?: return null
        val cy = ext.num("cy") ?: return null
        return RectF(l / EMU, t / EMU, (l + cx) / EMU, (t + cy) / EMU)
    }

    private fun masterType(t: String) = when (t) {
        "ctrTitle" -> "title"
        "subTitle", "", "obj" -> "body"
        else -> t
    }

    private fun findPh(tree: Element?, type: String, idx: String): Element? {
        tree ?: return null
        var byType: Element? = null
        for (sp in tree.kids("sp")) {
            val ph = sp.at("nvSpPr", "nvPr", "ph") ?: continue
            if (idx.isNotEmpty() && ph.getAttribute("idx") == idx) return sp
            if (byType == null && type.isNotEmpty() && ph.getAttribute("type") == type) byType = sp
        }
        return byType
    }

    private fun chain(sp: Element, ctx: Ctx): List<Element> {
        val ph = sp.at("nvSpPr", "nvPr", "ph") ?: return emptyList()
        val type = ph.getAttribute("type")
        val idx = ph.getAttribute("idx")
        val out = ArrayList<Element>()
        findPh(ctx.layoutTree, type, idx)?.let { out.add(it) }
        findPh(ctx.masterTree, masterType(type), "")?.let { out.add(it) }
        return out
    }

    private fun levelProps(chain: List<Element>, lvl: Int, ctx: Ctx, phType: String?): List<Element> {
        val name = "lvl${lvl + 1}pPr"
        val out = ArrayList<Element>()
        for (e in chain) e.at("txBody", "lstStyle", name)?.let { out.add(it) }
        if (phType != null) {
            val style = when (masterType(phType)) {
                "title" -> "titleStyle"
                "body" -> "bodyStyle"
                else -> "otherStyle"
            }
            ctx.master?.at("txStyles", style, name)?.let { out.add(it) }
        }
        return out
    }

    // ---------- Main conversion ----------

    fun convert(output: OutputStream, progress: (Int, Int) -> Unit) {
        loadTheme()
        val pres = xml("ppt/presentation.xml") ?: throw IllegalStateException("Not a valid .pptx file")
        val sz = pres.kid("sldSz") ?: throw IllegalStateException("Slide size missing")
        val wPt = (sz.num("cx") ?: 9144000L) / EMU
        val hPt = (sz.num("cy") ?: 6858000L) / EMU

        val presRels = rels("ppt/presentation.xml")
        val slidePaths = (pres.kid("sldIdLst")?.kids("sldId") ?: emptyList())
            .mapNotNull { presRels[it.getAttributeNS(NS_R, "id")]?.target }
        if (slidePaths.isEmpty()) throw IllegalStateException("No slides found")

        val doc = PdfDocument()
        try {
            for ((i, path) in slidePaths.withIndex()) {
                progress(i, slidePaths.size)
                val slide = xml(path) ?: continue
                val sRels = rels(path)

                val layoutPath = sRels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
                val layout = layoutPath?.let { xml(it) }
                val lRels = layoutPath?.let { rels(it) } ?: emptyMap()
                val masterPath = lRels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
                val master = masterPath?.let { xml(it) }
                val mRels = masterPath?.let { rels(it) } ?: emptyMap()

                val ctx = Ctx(layout?.at("cSld", "spTree"), master?.at("cSld", "spTree"), master)

                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(wPt.roundToInt(), hPt.roundToInt(), i + 1).create()
                )
                val c = page.canvas

                drawBackground(
                    c, wPt, hPt,
                    listOf<Pair<Element?, Map<String, Rel>>>(slide to sRels, layout to lRels, master to mRels)
                )

                if (slide.getAttribute("showMasterSp") != "0") {
                    if (layout?.getAttribute("showMasterSp") != "0") {
                        renderTree(c, master?.at("cSld", "spTree"), mRels, ctx, true)
                    }
                    renderTree(c, layout?.at("cSld", "spTree"), lRels, ctx, true)
                }
                renderTree(c, slide.at("cSld", "spTree"), sRels, ctx, false)

                doc.finishPage(page)
            }
            progress(slidePaths.size, slidePaths.size)
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }

    // ---------- Background ----------

    private fun drawBackground(c: Canvas, w: Float, h: Float, parts: List<Pair<Element?, Map<String, Rel>>>) {
        c.drawColor(Color.WHITE)
        for ((root, rels) in parts) {
            val bg = root?.at("cSld", "bg") ?: continue
            val pr = bg.kid("bgPr")
            if (pr != null) {
                val col = colorOf(pr)
                    ?: pr.at("gradFill", "gsLst")?.kids("gs")?.firstOrNull()?.let { colorFrom(it) }
                if (col != null) c.drawColor(col)
                pr.kid("blipFill")?.let { drawBlip(c, it, rels, RectF(0f, 0f, w, h)) }
            } else {
                bg.kid("bgRef")?.let { colorFrom(it) }?.let { c.drawColor(it) }
            }
            return
        }
    }

    // ---------- Shape tree ----------

    private fun renderTree(c: Canvas, tree: Element?, rels: Map<String, Rel>, ctx: Ctx, skipPh: Boolean) {
        tree ?: return
        for (e in tree.kids()) {
            val saved = c.save()
            try {
                when (e.localName) {
                    "sp" -> if (!(skipPh && e.at("nvSpPr", "nvPr", "ph") != null)) drawSp(c, e, rels, ctx)
                    "cxnSp" -> drawSp(c, e, rels, ctx)
                    "pic" -> drawPic(c, e, rels)
                    "graphicFrame" -> drawFrame(c, e, ctx)
                    "grpSp" -> {
                        val x = e.at("grpSpPr", "xfrm")
                        val off = x?.kid("off")
                        val ext = x?.kid("ext")
                        val cho = x?.kid("chOff")
                        val che = x?.kid("chExt")
                        if (off != null && ext != null && cho != null && che != null) {
                            val chw = (che.num("cx") ?: 1L).coerceAtLeast(1L)
                            val chh = (che.num("cy") ?: 1L).coerceAtLeast(1L)
                            val sx = (ext.num("cx") ?: chw).toFloat() / chw
                            val sy = (ext.num("cy") ?: chh).toFloat() / chh
                            c.translate((off.num("x") ?: 0L) / EMU, (off.num("y") ?: 0L) / EMU)
                            c.scale(sx, sy)
                            c.translate(-(cho.num("x") ?: 0L) / EMU, -(cho.num("y") ?: 0L) / EMU)
                        }
                        renderTree(c, e, rels, ctx, skipPh)
                    }
                }
            } catch (ex: Exception) {
                // skip shapes that fail to render
            } finally {
                c.restoreToCount(saved)
            }
        }
    }

    private fun drawSp(c: Canvas, sp: Element, rels: Map<String, Rel>, ctx: Ctx) {
        val spPr = sp.kid("spPr")
        val ch = chain(sp, ctx)
        val rect = xfrmRect(spPr?.kid("xfrm"))
            ?: ch.firstNotNullOfOrNull { xfrmRect(it.kid("spPr")?.kid("xfrm")) }
            ?: return
        val xf = spPr?.kid("xfrm")
        val rot = (xf?.num("rot") ?: 0L) / 60000f
        if (rot != 0f) c.rotate(rot, rect.centerX(), rect.centerY())

        val prst = spPr?.at("prstGeom")?.getAttribute("prst") ?: ""
        val isLine = sp.localName == "cxnSp" || prst == "line" || prst.contains("Connector")
        val spStyle = sp.kid("style")

        // Fill
        var fill: Int? = null
        if (spPr?.kid("noFill") == null && !isLine) {
            fill = colorOf(spPr)
                ?: spPr?.at("gradFill", "gsLst")?.kids("gs")?.firstOrNull()?.let { colorFrom(it) }
            if (fill == null && spPr?.kid("blipFill") == null) {
                val fr = spStyle?.kid("fillRef")
                if (fr != null && fr.getAttribute("idx") != "0") fill = colorFrom(fr)
            }
        }

        // Line
        val ln = spPr?.kid("ln")
        var lc: Int? = null
        if (ln?.kid("noFill") == null) {
            lc = colorOf(ln)
            if (lc == null) {
                val lr = spStyle?.kid("lnRef")
                if (lr != null && lr.getAttribute("idx") != "0") lc = colorFrom(lr)
            }
        }
        val lw = max((ln?.num("w") ?: 12700L) / EMU, 0.5f)

        val custom = spPr?.kid("custGeom") != null

        fun shape(p: Paint) {
            when (prst) {
                "ellipse" -> c.drawOval(rect, p)
                "roundRect" -> {
                    val rr = min(rect.width(), rect.height()) * 0.17f
                    c.drawRoundRect(rect, rr, rr, p)
                }
                "triangle" -> {
                    val path = Path()
                    path.moveTo(rect.centerX(), rect.top)
                    path.lineTo(rect.right, rect.bottom)
                    path.lineTo(rect.left, rect.bottom)
                    path.close()
                    c.drawPath(path, p)
                }
                else -> c.drawRect(rect, p)
            }
        }

        if (!custom && !isLine) {
            if (fill != null) {
                shape(Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill })
            }
            if (lc != null) {
                shape(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = lc; strokeWidth = lw
                })
            }
        }
        spPr?.kid("blipFill")?.let { drawBlip(c, it, rels, rect) }

        if (isLine && lc != null) {
            val fh = xf?.getAttribute("flipH") == "1"
            val fv = xf?.getAttribute("flipV") == "1"
            val x1 = if (fh) rect.right else rect.left
            val x2 = if (fh) rect.left else rect.right
            val y1 = if (fv) rect.bottom else rect.top
            val y2 = if (fv) rect.top else rect.bottom
            c.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = lc; strokeWidth = lw
            })
        }

        val tx = sp.kid("txBody")
        if (tx != null && !isLine) {
            val phType = sp.at("nvSpPr", "nvPr", "ph")?.getAttribute("type")
            val fontColor = spStyle?.kid("fontRef")?.let { colorFrom(it) }
            drawText(c, tx, rect, listOf(sp) + ch, ctx, phType, fontColor)
        }
    }

    // ---------- Pictures ----------

    private fun drawPic(c: Canvas, pic: Element, rels: Map<String, Rel>) {
        val rect = xfrmRect(pic.at("spPr", "xfrm")) ?: return
        val rot = (pic.at("spPr", "xfrm")?.num("rot") ?: 0L) / 60000f
        if (rot != 0f) c.rotate(rot, rect.centerX(), rect.centerY())
        val bf = pic.kid("blipFill") ?: return
        drawBlip(c, bf, rels, rect)
    }

    private fun drawBlip(c: Canvas, bf: Element, rels: Map<String, Rel>, dst: RectF) {
        val blip = bf.kid("blip") ?: return
        val id = blip.getAttributeNS(NS_R, "embed")
        val path = rels[id]?.target ?: return
        val bytes = files[path] ?: return

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return // unsupported (e.g. EMF/WMF)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return

        val w = bmp.width
        val h = bmp.height
        var src = Rect(0, 0, w, h)
        bf.kid("srcRect")?.let { s ->
            val l = (s.num("l") ?: 0L) / 100000f
            val t = (s.num("t") ?: 0L) / 100000f
            val r = (s.num("r") ?: 0L) / 100000f
            val b = (s.num("b") ?: 0L) / 100000f
            val cand = Rect((l * w).toInt(), (t * h).toInt(), (w - r * w).toInt(), (h - b * h).toInt())
            if (cand.width() > 0 && cand.height() > 0) src = cand
        }
        c.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        bmp.recycle()
    }

    // ---------- Tables ----------

    private fun drawFrame(c: Canvas, gf: Element, ctx: Ctx) {
        val rect = xfrmRect(gf.kid("xfrm")) ?: return
        val tbl = gf.getElementsByTagNameNS("*", "tbl").item(0) as? Element ?: return
        val grid = tbl.kid("tblGrid")?.kids("gridCol")?.map { (it.num("w") ?: 0L) / EMU } ?: return

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.LTGRAY; strokeWidth = 0.75f
        }
        var y = rect.top
        for (tr in tbl.kids("tr")) {
            val h = (tr.num("h") ?: 0L) / EMU
            var x0 = rect.left
            var ci = 0
            for (tc in tr.kids("tc")) {
                val span = (tc.num("gridSpan") ?: 1L).toInt()
                var w = 0f
                for (k in 0 until span) w += grid.getOrElse(ci + k) { 0f }
                val merged = tc.getAttribute("hMerge") == "1" || tc.getAttribute("vMerge") == "1"
                if (!merged) {
                    val cell = RectF(x0, y, x0 + w, y + h)
                    val tcPr = tc.kid("tcPr")
                    colorOf(tcPr)?.let {
                        c.drawRect(cell, Paint().apply { style = Paint.Style.FILL; color = it })
                    }
                    c.drawRect(cell, border)
                    tc.kid("txBody")?.let { drawText(c, it, cell, emptyList(), ctx, null, null) }
                }
                x0 += w
                ci += span
            }
            y += h
        }
    }

    // ---------- Text ----------

    private fun firstBullet(pPr: Element?, lv: List<Element>): String? {
        for (e in listOfNotNull(pPr) + lv) {
            if (e.kid("buNone") != null) return null
            val bc = e.kid("buChar")
            if (bc != null) {
                val font = e.kid("buFont")?.getAttribute("typeface") ?: ""
                return when {
                    font.contains("Wingdings", true) -> "▪"
                    font.contains("Symbol", true) -> "•"
                    else -> bc.getAttribute("char").ifEmpty { "•" }
                }
            }
            if (e.kid("buAutoNum") != null) return "•"
        }
        return null
    }

    private fun drawText(
        c: Canvas, tx: Element, r: RectF, chain: List<Element>,
        ctx: Ctx, phType: String?, styleColor: Int?
    ) {
        if (tx.getElementsByTagNameNS("*", "t").length == 0) return

        val bodyPrs = listOfNotNull(tx.kid("bodyPr")) + chain.mapNotNull { it.kid("txBody")?.kid("bodyPr") }
        fun bp(name: String): String {
            for (b in bodyPrs) {
                val v = b.getAttribute(name)
                if (v.isNotEmpty()) return v
            }
            return ""
        }
        val lIns = (bp("lIns").toLongOrNull() ?: 91440L) / EMU
        val tIns = (bp("tIns").toLongOrNull() ?: 45720L) / EMU
        val rIns = (bp("rIns").toLongOrNull() ?: 91440L) / EMU
        val bIns = (bp("bIns").toLongOrNull() ?: 45720L) / EMU
        val anchor = bp("anchor")
        val scale = (bodyPrs.firstNotNullOfOrNull { it.kid("normAutofit") }?.num("fontScale") ?: 100000L) / 100000f

        val sb = SpannableStringBuilder()
        val paras = tx.kids("p")

        for ((pi, p) in paras.withIndex()) {
            val pPr = p.kid("pPr")
            val lvl = (pPr?.getAttribute("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val lv = levelProps(chain, lvl, ctx, phType)
            val start = sb.length

            fun rAttr(rPr: Element?, name: String): String =
                pickAttr(listOf(rPr) + lv.map { it.kid("defRPr") }, name)

            fun appendRun(text: String, rPr: Element?) {
                if (text.isEmpty()) return
                val s = sb.length
                sb.append(text)
                val e = sb.length
                val size = (rAttr(rPr, "sz").toFloatOrNull() ?: 1800f) / 100f * scale
                sb.setSpan(SizeSpan(size), s, e, SPAN)
                val col = colorOf(rPr)
                    ?: lv.firstNotNullOfOrNull { colorOf(it.kid("defRPr")) }
                    ?: styleColor ?: themeColors["dk1"] ?: Color.BLACK
                sb.setSpan(ForegroundColorSpan(col), s, e, SPAN)
                val bold = rAttr(rPr, "b").let { it == "1" || it == "true" }
                val ital = rAttr(rPr, "i").let { it == "1" || it == "true" }
                val st = when {
                    bold && ital -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    ital -> Typeface.ITALIC
                    else -> 0
                }
                if (st != 0) sb.setSpan(StyleSpan(st), s, e, SPAN)
                val u = rAttr(rPr, "u")
                if (u.isNotEmpty() && u != "none") sb.setSpan(UnderlineSpan(), s, e, SPAN)
            }

            val runs = p.kids().filter { it.localName == "r" || it.localName == "fld" || it.localName == "br" }
            val hasText = runs.any { it.kid("t")?.textContent?.isNotEmpty() == true }

            if (hasText) {
                val bu = firstBullet(pPr, lv)
                if (!bu.isNullOrEmpty()) {
                    appendRun("$bu ", runs.firstOrNull { it.localName == "r" }?.kid("rPr"))
                }
            }
            for (run in runs) {
                when (run.localName) {
                    "r", "fld" -> appendRun(run.kid("t")?.textContent ?: "", run.kid("rPr"))
                    "br" -> sb.append("\n")
                }
            }
            if (sb.length == start) {
                // empty paragraph: keep its line height
                sb.append(" ")
                val size = (rAttr(p.kid("endParaRPr"), "sz").toFloatOrNull() ?: 1800f) / 100f * scale
                sb.setSpan(SizeSpan(size), start, sb.length, SPAN)
            }

            val pAttrs = listOf(pPr) + lv
            when (pickAttr(pAttrs, "algn")) {
                "ctr" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, SPAN)
                "r" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), start, sb.length, SPAN)
            }
            val marL = (pickAttr(pAttrs, "marL").toLongOrNull() ?: 0L) / EMU
            val indent = (pickAttr(pAttrs, "indent").toLongOrNull() ?: 0L) / EMU
            if (marL > 0f || indent != 0f) {
                sb.setSpan(
                    LeadingMarginSpan.Standard((marL + indent).toInt().coerceAtLeast(0), marL.toInt()),
                    start, sb.length, SPAN
                )
            }
            if (pi < paras.size - 1) sb.append("\n")
        }

        val width = (r.width() - lIns - rIns).toInt()
        if (width <= 0 || sb.isEmpty()) return

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f
            color = themeColors["dk1"] ?: Color.BLACK
        }
        val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()

        var y = r.top + tIns
        val avail = r.height() - tIns - bIns
        when (anchor) {
            "ctr" -> y += (avail - layout.height) / 2f
            "b" -> y += avail - layout.height
        }
        c.save()
        c.translate(r.left + lIns, y)
        layout.draw(c)
        c.restore()
    }
}

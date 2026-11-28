package com.pixelfire.smartpdfscanner

import android.util.Xml
import android.util.Base64
import android.graphics.BitmapFactory
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/** Bounded, read-only OOXML preview. Does not execute formulas, macros or external links. */
internal object OfficeLayoutPreview {
    data class Section(val name:String,val html:String)
    data class Preview(val sections:List<Section>,val note:String)
    private class Node(val name:String,val attrs:Map<String,String>){
        val kids=mutableListOf<Node>()
        val text=StringBuilder()
        fun a(key:String)=attrs[key].orEmpty()
        fun child(key:String)=kids.firstOrNull{it.name==key}
        fun children(key:String)=kids.filter{it.name==key}
        fun all(key:String):List<Node>{
            val result=mutableListOf<Node>()
            fun walk(n:Node){if(n.name==key)result.add(n);n.kids.forEach{walk(it)}}
            walk(this);return result
        }
        fun words()=all("t").joinToString(""){it.text.toString()}
    }
    private fun xml(bytes:ByteArray):Node{
        val p=Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true)
        p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false)
        p.setInput(bytes.inputStream(),null)
        val root=Node("root",emptyMap())
        val stack=mutableListOf(root)
        var count=0
        while(p.eventType!=XmlPullParser.END_DOCUMENT){
            when(p.eventType){
                XmlPullParser.DOCDECL -> error("Document declarations are not supported")
                XmlPullParser.START_TAG -> {
                    require(++count<=160000 && stack.size<=64){"Office XML exceeds preview limits"}
                    val a=mutableMapOf<String,String>()
                    for(i in 0 until p.attributeCount){
                        val key=p.getAttributeName(i)
                        val ns=p.getAttributeNamespace(i).orEmpty()
                        if(ns.contains("relationships"))a["r:"+key]=p.getAttributeValue(i)
                        else a[key]=p.getAttributeValue(i)
                    }
                    val n=Node(p.name,a);stack.last().kids.add(n);stack.add(n)
                }
                XmlPullParser.TEXT,XmlPullParser.CDSECT,XmlPullParser.ENTITY_REF -> {
                    val t=p.text.orEmpty()
                    require(stack.last().text.length+t.length<=500000){"Text exceeds preview limits"}
                    stack.last().text.append(t)
                }
                XmlPullParser.END_TAG -> if(stack.size>1)stack.removeAt(stack.lastIndex)
            }
            p.nextToken()
        }
        return root.kids.firstOrNull()?:error("Empty Office XML")
    }
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun color(s:String)=s.takeIf{it.matches(Regex("[0-9A-Fa-f]{6}"))}?.let{"#"+it}
    private fun path(base:String,target:String):String?{
        if(target.contains('\\')||target.contains(':')||target.contains('?')||target.contains('#'))return null
        val parts=mutableListOf<String>()
        val combined=if(target.startsWith('/'))target.removePrefix("/") else base.substringBeforeLast('/',"")+"/"+target
        for(p in combined.split('/'))when(p){
            "", "." -> Unit
            ".." -> if(parts.isEmpty())return null else parts.removeAt(parts.lastIndex)
            else -> parts.add(p)
        }
        return parts.joinToString("/")
    }
    private fun relations(parts:Map<String,ByteArray>,base:String):Map<String,String>{
        val rel=base.substringBeforeLast('/',"")+"/_rels/"+base.substringAfterLast('/')+".rels"
        return parts[rel]?.let{xml(it).children("Relationship").mapNotNull{n->
            if(n.a("TargetMode").equals("External",true))null
            else path(base,n.a("Target"))?.let{n.a("Id") to it}
        }.toMap()}?:emptyMap()
    }
    private class Images(val parts:Map<String,ByteArray>){
        var encoded=0L
        fun image(target:String?):String{
            val bytes=parts[target]?:return "<span class='missing'>[Image not previewed]</span>"
            val ext=target.orEmpty().substringAfterLast('.').lowercase(Locale.ROOT)
            val mime=when(ext){"png"->"image/png";"jpg","jpeg"->"image/jpeg";"webp"->"image/webp";else->return "<span class='missing'>[Image not previewed]</span>"}
            if(bytes.size>1_500_000||encoded+bytes.size>5_000_000L)return "<span class='missing'>[Image exceeds preview limit]</span>"
            val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
            if(options.outWidth<=0||options.outHeight<=0||options.outWidth.toLong()*options.outHeight>8_000_000L)return "<span class='missing'>[Image exceeds preview limit]</span>"
            encoded+=bytes.size
            return "<img alt='Embedded document image' src='data:"+mime+";base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)+"'>"
        }
    }
    private fun enabled(n:Node?)=n!=null&&n.a("val") !in listOf("0","false","off")
    private fun runStyle(props:Node?,drawing:Boolean=false):String{
        if(props==null)return ""
        val out=mutableListOf<String>()
        if(enabled(props.child("b"))||props.a("b")=="1")out.add("font-weight:bold")
        if(enabled(props.child("i"))||props.a("i")=="1")out.add("font-style:italic")
        if(enabled(props.child("u"))||props.a("u") !in listOf("","none"))out.add("text-decoration:underline")
        val size=if(drawing)props.a("sz").toFloatOrNull()?.div(100f) else props.child("sz")?.a("val")?.toFloatOrNull()?.div(2f)
        size?.takeIf{it.isFinite()}?.let{out.add("font-size:"+it.coerceIn(6f,80f)+"pt")}
        val c=props.child("color")?.a("val")?:props.child("solidFill")?.child("srgbClr")?.a("val").orEmpty()
        color(c)?.let{out.add("color:"+it)}
        return out.joinToString(";")
    }
    private fun paragraph(n:Node,rels:Map<String,String>,images:Images):String{
        val props=n.child("pPr")
        val align=when(props?.child("jc")?.a("val")?:props?.a("algn")){"center","ctr"->"center";"right","r"->"right";"both","just"->"justify";else->"left"}
        val heading=props?.child("pStyle")?.a("val")?.startsWith("Heading",true)==true
        val out=StringBuilder("<p style='text-align:$align;"+(if(heading)"font-weight:bold;font-size:1.25em" else "")+"'>")
        fun visit(node:Node){
            when(node.name){
                "r","fld" -> {
                    out.append("<span style='").append(runStyle(node.child("rPr"))).append("'>")
                    node.kids.forEach{ch->when(ch.name){
                        "t"->out.append(esc(ch.text.toString()))
                        "tab"->out.append("&#160;&#160;&#160;&#160;")
                        "br","cr"->out.append("<br>")
                        "drawing","pict"->ch.all("blip").forEach{out.append(images.image(rels[it.a("r:embed")]))}
                    }}
                    out.append("</span>")
                }
                "hyperlink","smartTag","sdt","sdtContent","ins" -> node.kids.forEach{visit(it)}
                else -> Unit
            }
        }
        n.kids.forEach{visit(it)}
        return out.append("</p>").toString()
    }
    private fun word(root:Node,rels:Map<String,String>,images:Images):List<Section>{
        val content=root.child("body")?:error("No Word body")
        val sections=mutableListOf<Section>()
        val out=StringBuilder()
        fun flush(){if(out.isNotEmpty()){require(sections.size<100){"Word preview exceeds section limit"};sections.add(Section("Section "+(sections.size+1),"<article>"+out.toString()+"</article>"));out.setLength(0)}}
        for(n in content.kids){
            when(n.name){
                "p"->out.append(paragraph(n,rels,images))
                "tbl"->{
                    out.append("<div class='wide'><table>")
                    for(row in n.children("tr").take(150)){
                        out.append("<tr>")
                        for(cell in row.children("tc").take(40)){
                            val span=cell.child("tcPr")?.child("gridSpan")?.a("val")?.toIntOrNull()?.coerceIn(1,40)?:1
                            val fill=color(cell.child("tcPr")?.child("shd")?.a("fill").orEmpty())
                            out.append("<td colspan='$span' style='background:").append(fill?:"#ffffff").append("'>")
                            cell.children("p").forEach{out.append(paragraph(it,rels,images))}
                            out.append("</td>")
                        }
                        out.append("</tr>")
                    }
                    out.append("</table></div>")
                    if(n.children("tr").size>150||n.children("tr").any{it.children("tc").size>40})out.append("<p class='missing'>Table preview limited to 150 rows and 40 cells per row.</p>")
                }
            }
            require(out.length<=12_000_000){"Word section too large"}
            if(out.length>180000)flush()
        }
        flush();return sections
    }
    internal fun columnIndex(ref:String):Int{
        var n=0
        val letters=ref.takeWhile{it in 'A'..'Z'||it in 'a'..'z'}.uppercase(Locale.ROOT)
        if(letters.length !in 1..3)return -1
        for(c in letters)n=n*26+(c-'A'+1)
        return n-1
    }
    private fun colName(value:Int):String{
        var n=value+1;var s=""
        while(n>0){n--;s=('A'+n%26)+s;n/=26}
        return s
    }
    private fun sheet(root:Node,shared:List<String>):String{
        val rows=root.child("sheetData")?.children("row").orEmpty()
        val visible=rows.take(150)
        var cols=1
        val mapped=visible.mapIndexed{index,row->
            val cells=mutableMapOf<Int,String>()
            var next=0
            for(cell in row.children("c")){
                val explicit=columnIndex(cell.a("r"))
                val col=if(explicit<0)next else explicit
                next=col+1
                if(col !in 0..39)continue
                val raw=cell.child("v")?.text?.toString().orEmpty()
                val value=when(cell.a("t")){
                    "s"->shared.getOrNull(raw.toIntOrNull()?:-1).orEmpty()
                    "inlineStr"->cell.child("is")?.words().orEmpty()
                    "b"->if(raw=="1")"TRUE" else "FALSE"
                    else->raw
                }
                val shown=if(value.isEmpty()&&cell.child("f")!=null)"[Formula: no cached value]" else value
                cells[col]=shown.take(1000);cols=maxOf(cols,col+1)
            }
            (row.a("r").toIntOrNull()?:index+1) to cells
        }
        val out=StringBuilder("<p class='missing'>Grid preview: up to 150 stored rows, columns A–AN and 1,000 characters per cell. Formula values use the saved cache; no recalculation or number formatting.</p><div class='wide'><table class='sheet'><tr><th></th>")
        for(c in 0 until cols)out.append("<th>").append(colName(c)).append("</th>")
        out.append("</tr>")
        for((number,cells) in mapped){
            out.append("<tr><th>").append(number).append("</th>")
            for(c in 0 until cols)out.append("<td>").append(esc(cells[c].orEmpty())).append("</td>")
            out.append("</tr>")
            require(out.length<=4_000_000){"Worksheet preview too large"}
        }
        return out.append("</table></div>").toString()
    }
    private fun slide(root:Node,rels:Map<String,String>,images:Images,cx:Double,cy:Double):String{
        val common=root.child("cSld")
        val tree=common?.child("spTree")?:error("No slide content")
        val bg=color(common.child("bg")?.child("bgPr")?.child("solidFill")?.child("srgbClr")?.a("val").orEmpty())?:"#ffffff"
        val ratio=(cy/cx*100.0).coerceIn(25.0,200.0)
        val out=StringBuilder("<div class='slide' style='padding-top:$ratio%;background:$bg'>")
        val fallback=StringBuilder()
        for(n in tree.kids.take(300)){
            if(n.name !in listOf("sp","pic","graphicFrame"))continue
            val xfrm=n.child("spPr")?.child("xfrm")?:n.child("xfrm")
            val off=xfrm?.child("off")
            val ext=xfrm?.child("ext")
            val x=off?.a("x")?.toDoubleOrNull();val y=off?.a("y")?.toDoubleOrNull()
            val w=ext?.a("cx")?.toDoubleOrNull();val h=ext?.a("cy")?.toDoubleOrNull()
            val body=when(n.name){
                "pic"->images.image(rels[n.all("blip").firstOrNull()?.a("r:embed")])
                "sp"->n.child("txBody")?.children("p")?.joinToString(""){p->
                    "<p>"+p.children("r").joinToString(""){rr->"<span style='"+runStyle(rr.child("rPr"),true)+"'>"+esc(rr.words())+"</span>"}+"</p>"
                }.orEmpty()
                else->"<span class='missing'>[Chart or table not rendered]</span>"
            }
            if(body.isBlank())continue
            if(x==null||y==null||w==null||h==null||listOf(x,y,w,h).any{!it.isFinite()}||w<=0||h<=0){
                fallback.append("<p>").append(body).append("</p>")
                continue
            }
            val fill=color(n.child("spPr")?.child("solidFill")?.child("srgbClr")?.a("val").orEmpty())?:"transparent"
            val l=(x/cx*100).coerceIn(-100.0,200.0);val t=(y/cy*100).coerceIn(-100.0,200.0)
            val ww=(w/cx*100).coerceIn(0.1,200.0);val hh=(h/cy*100).coerceIn(0.1,200.0)
            out.append("<div class='shape' style='left:$l%;top:$t%;width:$ww%;height:$hh%;background:$fill'>").append(body).append("</div>")
        }
        return out.append("</div>").append(if(fallback.isNotEmpty())"<p class='missing'>Content with unavailable slide positioning:</p>"+fallback else "").toString()
    }
    fun read(input:InputStream,ext:String):Preview{
        require(ext in setOf("docx","xlsx","pptx"))
        val parts=mutableMapOf<String,ByteArray>()
        val pattern=Regex("(word|xl|ppt)/.*(\\.xml|\\.rels|\\.png|\\.jpe?g|\\.webp)",RegexOption.IGNORE_CASE)
        var total=0L;var kept=0L;var entries=0
        ZipInputStream(input).use{zip->
            val buffer=ByteArray(8192)
            while(true){
                val entry=zip.nextEntry?:break
                require(++entries<=5000){"Too many Office entries"}
                val wanted=!entry.isDirectory&&entry.name.matches(pattern)&&!entry.name.split('/').contains("..")
                val out=if(wanted)ByteArrayOutputStream() else null
                while(true){
                    val n=zip.read(buffer);if(n<0)break
                    total+=n;require(total<=96L*1024*1024){"Expanded Office file exceeds 96 MB"}
                    if(out!=null){kept+=n;require(kept<=24L*1024*1024&&out.size()+n<=8*1024*1024){"Office preview memory limit"};out.write(buffer,0,n)}
                }
                if(out!=null)parts[entry.name]=out.toByteArray()
                zip.closeEntry()
            }
        }
        val images=Images(parts)
        val sections=when(ext){
            "docx"->word(xml(parts["word/document.xml"]?:error("Word document missing")),relations(parts,"word/document.xml"),images)
            "xlsx"->{
                val base="xl/workbook.xml"
                val workbook=xml(parts[base]?:error("Workbook missing"))
                val rel=relations(parts,base)
                val shared=parts["xl/sharedStrings.xml"]?.let{xml(it).children("si").map{n->n.words()}}?:emptyList()
                workbook.child("sheets")?.children("sheet").orEmpty().take(30).mapNotNull{n->
                    val target=rel[n.a("r:id")]?:return@mapNotNull null
                    val bytes=parts[target]?:return@mapNotNull null
                    Section(n.a("name").ifBlank{"Worksheet"},sheet(xml(bytes),shared))
                }
            }
            else->{
                val base="ppt/presentation.xml"
                val p=xml(parts[base]?:error("Presentation missing"))
                val rel=relations(parts,base)
                val cx=p.child("sldSz")?.a("cx")?.toDoubleOrNull()?.takeIf{it.isFinite()&&it>0}?:9144000.0
                val cy=p.child("sldSz")?.a("cy")?.toDoubleOrNull()?.takeIf{it.isFinite()&&it>0}?:6858000.0
                p.child("sldIdLst")?.children("sldId").orEmpty().take(50).mapIndexedNotNull{i,n->
                    val target=rel[n.a("r:id")]?:return@mapIndexedNotNull null
                    val bytes=parts[target]?:return@mapIndexedNotNull null
                    Section("Slide "+(i+1),slide(xml(bytes),relations(parts,target),images,cx,cy))
                }
            }
        }
        require(sections.isNotEmpty()){"No supported content found"}
        require(sections.sumOf{it.html.length.toLong()}<=12_000_000L){"Office preview exceeds output limit"}
        val note=when(ext){
            "docx"->"Word layout preview • direct formatting, basic tables and embedded images; pagination, themes and complex layout may differ."
            "xlsx"->"Spreadsheet preview • first 30 sheets; cached values, not formulas or original styles."
            else->"Presentation preview • first 50 slides; basic shapes and images. Masters, charts, effects and animations are not reproduced."
        }
        return Preview(sections,note)
    }
    fun html(section:Section):String="""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'none'; connect-src 'none'; base-uri 'none'; form-action 'none'"><style>
        *{box-sizing:border-box}body{margin:0;padding:12px;background:#eaf0f8;color:#172b4d;font:15px Arial,sans-serif}article{background:white;padding:20px;border:1px solid #dce7f5;min-height:60vh}p{white-space:pre-wrap;margin:0 0 8px;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{border-collapse:collapse;background:white}td,th{border:1px solid #d5deea;padding:7px;vertical-align:top;min-width:45px}td p{margin:0}th{background:#edf3fa;font-weight:600}.wide{overflow:auto;max-width:100%;margin-bottom:12px}.sheet td{min-width:105px;max-width:250px;white-space:pre-wrap;overflow-wrap:anywhere}.missing{font-size:12px;color:#52627a}.slide{position:relative;background:white;overflow:hidden;border:1px solid #dce7f5}.shape{position:absolute;overflow:hidden;font-size:10px}.shape img{width:100%;height:100%;object-fit:contain}.shape p{margin:0;line-height:1.1}.shape span{font-size:inherit!important}
        </style></head><body>"""+section.html+"</body></html>"
}

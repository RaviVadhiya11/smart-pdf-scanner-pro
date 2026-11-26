package com.pixelfire.smartpdfscanner

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal object InvoiceDocument {
    val zero = BigDecimal.ZERO.setScale(2)
    fun money(n: BigDecimal): BigDecimal = n.setScale(2, RoundingMode.HALF_UP)
    fun format(n: BigDecimal) = money(n).toPlainString()
    data class Item(val description:String,val hsn:String,val qty:BigDecimal,val rate:BigDecimal,val gst:BigDecimal) {
        val base get() = money(qty.multiply(rate))
    }
    data class Draft(val kind:String,val number:String,val date:String,val due:String,val business:String,val businessAddress:String,
        val contact:String,val gstin:String,val customer:String,val customerAddress:String,val customerGstin:String,val supply:String,
        val items:List<Item>,val discount:BigDecimal,val interstate:Boolean,val notes:String,val template:Int,val logo:ByteArray?,val signature:ByteArray?)
    data class Amounts(val base:BigDecimal,val discount:BigDecimal,val net:BigDecimal,val cgst:BigDecimal,val sgst:BigDecimal,val igst:BigDecimal,val total:BigDecimal)
    fun totals(d:Draft):Amounts {
        require(d.items.isNotEmpty() && d.items.size<=100)
        val base=d.items.fold(zero){a,i->a+i.base}
        require(d.discount>=zero && d.discount<=base){"Discount cannot exceed subtotal"}
        var allocated=zero; var cgst=zero; var sgst=zero; var igst=zero
        d.items.forEachIndexed { index,i->
            val cut=if(index==d.items.lastIndex)d.discount-allocated else if(base.signum()==0)zero else money(d.discount.multiply(i.base).divide(base,12,RoundingMode.HALF_UP)).coerceAtMost(d.discount-allocated).coerceAtMost(i.base)
            allocated+=cut
            val taxable=(i.base-cut).coerceAtLeast(zero)
            if(d.interstate)igst+=money(taxable.multiply(i.gst).movePointLeft(2))
            else {
                val half=money(taxable.multiply(i.gst).movePointLeft(2).divide(BigDecimal(2)))
                cgst+=half;sgst+=half
            }
        }
        val net=base-d.discount
        return Amounts(base,d.discount,net,cgst,sgst,igst,net+cgst+sgst+igst)
    }

    fun pdf(d:Draft):ByteArray {
        val t=totals(d)
        val accent=when(d.template){1->0xFF303846.toInt();2->0xFF087C56.toInt();3->0xFF6546C8.toInt();4->0xFF9B3434.toInt();5->0xFFB17C16.toInt();else->0xFF205FBC.toInt()}
        val ink=0xFF18283D.toInt();val muted=0xFF536477.toInt()
        val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val doc=PdfDocument()
        var page:PdfDocument.Page?=null
        var canvas:Canvas?=null
        var pageNo=0
        var y=0f
        fun text(s:String,x:Float,yy:Float,size:Float=10f,color:Int=ink,bold:Boolean=false,right:Boolean=false){
            p.color=color;p.textSize=size;p.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.textAlign=if(right)Paint.Align.RIGHT else Paint.Align.LEFT
            canvas!!.drawText(s,x,yy,p)
            p.textAlign=Paint.Align.LEFT
        }
        fun rect(x:Float,yy:Float,w:Float,h:Float,color:Int){p.color=color;canvas!!.drawRect(x,yy,x+w,yy+h,p)}
        fun wrapped(s:String,maxWidth:Float,size:Float=10f,bold:Boolean=false):List<String>{
            p.textSize=size;p.typeface=if(bold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val result=mutableListOf<String>()
            for(paragraph in s.replace("\r","").split('\n')){
                if(paragraph.isBlank()){result.add("");continue}
                var rest=paragraph.trim()
                while(rest.isNotEmpty()){
                    var count=p.breakText(rest,true,maxWidth,null).coerceAtLeast(1)
                    if(count<rest.length){val space=rest.lastIndexOf(' ',count-1);if(space>0)count=space}
                    result.add(rest.take(count).trim());rest=rest.drop(count).trimStart()
                }
            }
            return result
        }
        fun image(bytes:ByteArray?,x:Float,yy:Float,w:Float,h:Float){
            if(bytes==null)return
            val bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?:return
            try{val scale=minOf(w/bmp.width,h/bmp.height);val bw=bmp.width*scale;val bh=bmp.height*scale
                canvas!!.drawBitmap(bmp,null,RectF(x,yy,x+bw,yy+bh),p)
            }finally{bmp.recycle()}
        }
        fun finish(){
            if(page==null)return
            rect(34f,801f,527f,1f,0xFFD8E1EC.toInt())
            text("DocNexa • "+d.number,34f,816f,9f,muted)
            text("Page "+pageNo,561f,816f,9f,muted,right=true)
            doc.finishPage(page!!);page=null;canvas=null
        }
        fun newPage(){
            finish();pageNo++;page=doc.startPage(PdfDocument.PageInfo.Builder(595,842,pageNo).create());canvas=page!!.canvas
            canvas!!.drawColor(Color.WHITE)
            rect(0f,0f,595f,8f,accent)
            if(pageNo==1){
                image(d.logo,34f,26f,58f,48f)
                val bx=if(d.logo==null)34f else 104f
                var hy=38f
                wrapped(d.business,345f-(bx-34f),16f,true).forEach{text(it,bx,hy,16f,ink,true);hy+=19f}
                wrapped(listOf(d.businessAddress,d.contact,if(d.gstin.isNotBlank())"GSTIN: "+d.gstin else "").filter{it.isNotBlank()}.joinToString("\n"),345f-(bx-34f)).forEach{text(it,bx,hy,10f,muted);hy+=13f}
                text(d.kind.uppercase(Locale.ROOT),561f,38f,16f,accent,true,true)
                text("No. "+d.number,561f,56f,10f,ink,true,true)
                text("Date: "+d.date,561f,70f,10f,muted,right=true)
                if(d.due.isNotBlank())text("Due: "+d.due,561f,84f,10f,muted,right=true)
                y=maxOf(hy,90f)+14f
                rect(34f,y,527f,1f,0xFFD8E1EC.toInt());y+=17f
                text("BILL TO / RECIPIENT",34f,y,10f,accent,true);y+=15f
                wrapped(d.customer,527f,12f,true).forEach{text(it,34f,y,12f,ink,true);y+=15f}
                wrapped(listOf(d.customerAddress,if(d.customerGstin.isNotBlank())"GSTIN: "+d.customerGstin else "",if(d.supply.isNotBlank())"Place of supply: "+d.supply else "").filter{it.isNotBlank()}.joinToString("\n"),527f).forEach{text(it,34f,y);y+=13f}
                y+=10f
            }else{
                text(d.kind+" • "+d.number,34f,37f,14f,accent,true);y=52f
            }
        }
        fun tableHeader(){
            rect(34f,y,527f,25f,accent)
            text("Description / HSN",43f,y+16f,10f,Color.WHITE,true)
            text("Qty",348f,y+16f,10f,Color.WHITE,true,true)
            text("Rate",424f,y+16f,10f,Color.WHITE,true,true)
            text("GST",478f,y+16f,10f,Color.WHITE,true,true)
            text("Amount",552f,y+16f,10f,Color.WHITE,true,true);y+=25f
        }
        try{
            newPage();tableHeader()
            d.items.forEachIndexed{index,i->
                val lines=wrapped(i.description,255f)+if(i.hsn.isBlank())emptyList() else wrapped("HSN/SAC: "+i.hsn,255f)
                val h=maxOf(29f,lines.size*13f+12f)
                if(y+h>779f){newPage();tableHeader()}
                rect(34f,y,527f,h,if(index%2==0)0xFFF3F6FA.toInt() else Color.WHITE)
                lines.forEachIndexed{n,line->text(line,43f,y+15f+n*13f)}
                text(i.qty.stripTrailingZeros().toPlainString(),348f,y+15f,right=true)
                text(format(i.rate),424f,y+15f,right=true)
                text(i.gst.stripTrailingZeros().toPlainString()+"%",478f,y+15f,right=true)
                text(format(i.base),552f,y+15f,right=true)
                y+=h
            }
            if(y+180f>780f)newPage()
            y+=18f
            fun amount(name:String,value:BigDecimal){text(name,324f,y,11f,muted);text("INR "+format(value),552f,y,11f,ink,true,true);y+=18f}
            amount("Subtotal",t.base);if(t.discount.signum()>0)amount("Discount",-t.discount)
            if(t.cgst.signum()>0||t.sgst.signum()>0){amount("Taxable value",t.net);amount("CGST",t.cgst);amount("SGST",t.sgst)}
            if(t.igst.signum()>0){amount("Taxable value",t.net);amount("IGST",t.igst)}
            rect(315f,y-3f,246f,34f,accent);text("TOTAL",325f,y+18f,12f,Color.WHITE,true)
            text("INR "+format(t.total),551f,y+18f,13f,Color.WHITE,true,true);y+=49f
            if(d.notes.isNotBlank()){
                if(y+35f>780f)newPage()
                text("NOTES / PAYMENT TERMS",34f,y,10f,accent,true);y+=15f
                wrapped(d.notes,527f).forEach{if(y>779f)newPage();text(it,34f,y);y+=13f}
            }
            if(d.signature!=null){if(y+88f>780f)newPage();image(d.signature,405f,y,145f,55f);y+=65f;text("Authorized signature (image)",552f,y,10f,muted,right=true)}
            finish()
            return ByteArrayOutputStream().also{doc.writeTo(it)}.toByteArray()
        }finally{runCatching{finish()};doc.close()}
    }
}

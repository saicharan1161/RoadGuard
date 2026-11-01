package com.roadguard.ai

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.*

/** Lightweight native views: no animation/video thumbnails competing with recording. */
object LibraryStyle {
    val background=Color.rgb(12,20,32)
    val ink=Color.rgb(239,245,255)
    val muted=Color.rgb(169,187,208)
    fun dp(c:Context,n:Int)=(c.resources.displayMetrics.density*n).toInt()
    fun panel(c:Context)=LinearLayout(c).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12))
        background=GradientDrawable().apply { setColor(Color.rgb(24,38,55)); cornerRadius=dp(c,16).toFloat() }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,dp(c,8),0,dp(c,8)) }
    }
    fun text(c:Context,value:String,size:Float=16f,secondary:Boolean=false)=TextView(c).apply {
        text=value; textSize=size; setTextColor(if(secondary) muted else ink)
        setPadding(0,dp(c,4),0,dp(c,6))
        if(size>=20) setTypeface(null,Typeface.BOLD)
    }
    fun button(c:Context,label:String,action:()->Unit)=Button(c).apply {
        text=label; isAllCaps=false; textSize=15f; setTextColor(ink)
        minHeight=dp(c,48)
        backgroundTintList=android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(Color.rgb(45,54,65),Color.rgb(25,86,119)))
        setOnClickListener { action() }
    }
    fun title(name:String):String {
        val match=Regex("^(\\d{4}-\\d{2}-\\d{2})_([A-Za-z]+)_(\\d{2})-(\\d{2})-(\\d{2})").find(name)
        return match?.let { "${it.groupValues[3]}:${it.groupValues[4]}:${it.groupValues[5]}  ·  ${it.groupValues[2]}" } ?: name
    }
    fun day(name:String)=Regex("^\\d{4}-\\d{2}-\\d{2}").find(name)?.value ?: "Earlier recordings"
}

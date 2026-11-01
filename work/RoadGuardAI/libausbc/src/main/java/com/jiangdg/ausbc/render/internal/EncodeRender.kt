/*
 * Copyright 2017-2023 Jiangdg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jiangdg.ausbc.render.internal

import android.content.Context
import android.opengl.EGLContext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.graphics.*
import android.view.Surface
import com.jiangdg.ausbc.R
import com.jiangdg.ausbc.render.env.EGLEvn

/** Inherit from AbstractFboRender
 *      render data to EGL from fbo and encode it
 *
 * @author Created by jiangdg on 2021/12/27
 */
class EncodeRender(context: Context): AbstractRender(context) {

    private var mEgl: EGLEvn? = null
    private val stamp=Bitmap.createBitmap(404,32,Bitmap.Config.ARGB_8888)
    private val canvas=Canvas(stamp)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);textSize=20f;color=Color.WHITE}
    private val date=java.text.SimpleDateFormat("EEE yyyy-MM-dd HH:mm:ss Z",java.util.Locale.ENGLISH)
    private val tex=IntArray(1)
    private var tick=Long.MIN_VALUE
    private var clockLoc= -1
    private var sizeLoc= -1
    override fun init(){
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);createTexture(tex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,stamp,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        clockLoc=GLES20.glGetUniformLocation(mProgram,"uClock");sizeLoc=GLES20.glGetUniformLocation(mProgram,"uStampSize")
    }
    override fun beforeDraw(){
        val now=System.currentTimeMillis()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex[0])
        if(tick!=now/1000){
            tick=now/1000;date.timeZone=java.util.TimeZone.getDefault();stamp.eraseColor(0xb0000000.toInt())
            canvas.drawText(date.format(java.util.Date(now)),6f,23f,paint)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,stamp)
        }
        val w=minOf(mWidth*.73f,maxOf(200f,mWidth*.40f))
        GLES20.glUniform1i(clockLoc,1);GLES20.glUniform2f(sizeLoc,w,w*32/404)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    fun initEGLEvn(glContext: EGLContext) {
        mEgl = EGLEvn()
        mEgl?.initEgl(glContext)
    }

    fun setupSurface(surface: Surface) {
        mEgl?.setupSurface(surface)
        mEgl?.eglMakeCurrent()
    }

    fun swapBuffers(timeStamp: Long) {
        mEgl?.setPresentationTime(timeStamp)
        mEgl?.swapBuffers()
    }

    override fun clear() {
        GLES20.glDeleteTextures(1,tex,0);stamp.recycle()
        mEgl?.releaseElg()
        mEgl = null
    }

    override fun getVertexSourceId(): Int = R.raw.base_vertex

    override fun getFragmentSourceId(): Int = R.raw.stamped_fragment
}

precision mediump float;
uniform sampler2D uTextureSampler;
uniform sampler2D uClock;
uniform vec2 uStampSize;
varying vec2 vTextureCoord;
void main(){
    vec4 image=texture2D(uTextureSampler,vTextureCoord);
    vec2 p=(gl_FragCoord.xy-vec2(6.0,6.0))/max(uStampSize,vec2(1.0));
    if(uStampSize.x>0.0 && p.x>=0.0 && p.y>=0.0 && p.x<1.0 && p.y<1.0){
        vec4 stamp=texture2D(uClock,vec2(p.x,1.0-p.y));
        image.rgb=stamp.rgb+(1.0-stamp.a)*image.rgb;
    }
    gl_FragColor=vec4(image.rgb,1.0);
}

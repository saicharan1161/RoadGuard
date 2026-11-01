#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTextureSampler;
uniform float uShadowLift;
varying vec2 vTextureCoord;
void main()
{
    vec4 c = texture2D(uTextureSampler, vTextureCoord);
    float y = dot(c.rgb, vec3(0.299,0.587,0.114));
    gl_FragColor = vec4(clamp(c.rgb * (1.0 + uShadowLift*(1.0-y)*(1.0-y)),0.0,1.0),c.a);
}

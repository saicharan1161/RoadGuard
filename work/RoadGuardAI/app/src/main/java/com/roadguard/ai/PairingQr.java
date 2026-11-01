package com.roadguard.ai;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** Render at final display size, preserving integer modules and the quiet zone. */
public final class PairingQr {
    public static int[] pixels(String payload, int size) throws Exception {
        for(ErrorCorrectionLevel level:new ErrorCorrectionLevel[]{ErrorCorrectionLevel.M,ErrorCorrectionLevel.L,ErrorCorrectionLevel.Q,ErrorCorrectionLevel.H}) {
        java.util.Map<EncodeHintType,Object> hints=new java.util.EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION,level);
        BitMatrix matrix=new MultiFormatWriter().encode(payload,BarcodeFormat.QR_CODE,size,size,hints);
        if(matrix.getWidth()!=size || matrix.getHeight()!=size) throw new IllegalArgumentException("Screen too small for QR; use code");
        int[] pixels=new int[size*size];
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) pixels[y*size+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        try {
            String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,pixels)))).getText();
            if(payload.equals(decoded)) return pixels;
        } catch(ReaderException ignored) { }
        }
        throw new IllegalArgumentException("QR could not be validated at this display size; use code");
    }
}

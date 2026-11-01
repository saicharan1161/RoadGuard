import com.roadguard.ai.PairingQr;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

public class PairingQrHarness {
    public static void main(String[] args) throws Exception {
        java.util.Random random=new java.util.Random(51);
        for(int sample=0;sample<20;sample++) {
        byte[] pin=new byte[32], secret=new byte[32]; random.nextBytes(pin); random.nextBytes(secret);
        String payload="RG1|0.5.1-alpha|10.80.21.246|"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(pin)+"|"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        for(int size:new int[]{204,256,307,384,500}) {
            int[] pixels=PairingQr.pixels(payload,size);
            String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size,size,pixels)))).getText();
            if(!payload.equals(decoded)) throw new AssertionError("QR mismatch "+size);
        }
        }
        System.out.println("PASS 100 QR round-trips: 20 distinct credentials at 204, 256, 307, 384, 500 pixels");
    }
    static String repeat(char c,int n) { char[] chars=new char[n]; java.util.Arrays.fill(chars,c); return new String(chars); }
}

import com.roadguard.ai.LinkPolicy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class LinkPolicyHarness {
    private static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        LinkPolicy.PairingGate gate=new LinkPolicy.PairingGate();
        check(!gate.consume("",0));
        gate.open("12345678","qr-secret",100);
        check(gate.consume("12345678",101)); check(!gate.consume("12345678",102));
        gate.open("12345678","qr-secret",100); check(gate.consume("qr-secret",101));
        gate.open("12345678","qr-secret",100); check(!gate.consume("12345678",120100));
        gate.open("12345678","qr-secret",100);
        for(int i=0;i<5;i++) check(!gate.consume("wrong",101));
        check(!gate.consume("12345678",102));
        gate.open("12345678","qr-secret",100); gate.close(); check(!gate.consume("12345678",101));
        gate.open("12345678","qr-secret",100);
        CountDownLatch go=new CountDownLatch(1), done=new CountDownLatch(20);
        AtomicInteger wins=new AtomicInteger();
        for(int i=0;i<20;i++) new Thread(()-> { try { go.await(); if(gate.consume("qr-secret",101)) wins.incrementAndGet(); } catch(Exception e) { throw new RuntimeException(e); } finally { done.countDown(); } }).start();
        go.countDown(); done.await(); check(wins.get()==1);
        check(Arrays.equals(LinkPolicy.range(null,100),new long[]{0,99}));
        check(Arrays.equals(LinkPolicy.range("bytes=20-",100),new long[]{20,99}));
        check(Arrays.equals(LinkPolicy.range("bytes=20-999",100),new long[]{20,99}));
        check(Arrays.equals(LinkPolicy.range("bytes=20-20",100),new long[]{20,20}));
        for(String bad:new String[]{"bytes=-1-2","bytes=100-","bytes=40-20","bytes=1-2,4-5","bytes=999999999999999999999-","anything"}) {
            try { LinkPolicy.range(bad,100); throw new AssertionError(bad); } catch(IllegalArgumentException expected) {}
        }
        System.out.println("PASS: expired/used/wrong codes, five-attempt lockout, QR/code alternatives, revocation, concurrent single-use pairing, valid and invalid byte ranges");
    }
}
